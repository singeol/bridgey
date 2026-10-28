import AVFoundation
import CoreMedia
import Foundation
import VideoToolbox

/*
 * Screen sharing, phone to Mac: H.264 access units in, decoded frames out, with VideoToolbox's hardware
 * decoder. Every key frame carries its SPS and PPS, so a new format (a rotation) rebuilds the
 * session from the key frame itself. Used from one serial queue per stream.
 */

final class ScreenVideoDecoder {
    enum Outcome {
        case decoded(CVImageBuffer, decodeMs: Double)
        /// The frame cannot be decoded until a key frame arrives (no format yet, or the decoder failed).
        case needsKeyFrame
    }

    private var session: VTDecompressionSession?
    private var format: CMVideoFormatDescription?
    private var parameterSets: [Data] = []
    /// The decoded size (from the SPS), for the window's aspect ratio.
    private(set) var dimensions: CMVideoDimensions?

    deinit { invalidate() }

    func invalidate() {
        if let session {
            VTDecompressionSessionWaitForAsynchronousFrames(session)
            VTDecompressionSessionInvalidate(session)
        }
        session = nil
    }

    /// Decodes one access unit synchronously.
    func decode(_ frame: ScreenAssembledFrame) -> Outcome {
        let started = DispatchTime.now().uptimeNanoseconds
        let unit = AnnexB.avcc(frame.data)
        if let sps = unit.sps, !unit.pps.isEmpty {
            let sets = [sps] + unit.pps
            if sets != parameterSets || session == nil {
                guard rebuild(sets) else { return .needsKeyFrame }
            }
        }
        guard let session, let format, !unit.avcc.isEmpty else { return .needsKeyFrame }
        guard let sample = Self.sampleBuffer(unit.avcc, format: format) else { return .needsKeyFrame }
        let result = DecodeResult()
        var info = VTDecodeInfoFlags()
        let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [], infoFlagsOut: &info) { status, _, buffer, _, _ in
            result.status = status
            result.image = buffer
        }
        guard status == noErr, result.status == noErr, let image = result.image else {
            if status == kVTInvalidSessionErr { invalidate() }
            return .needsKeyFrame
        }
        let elapsed = Double(DispatchTime.now().uptimeNanoseconds - started) / 1_000_000
        return .decoded(image, decodeMs: elapsed)
    }

    private func rebuild(_ sets: [Data]) -> Bool {
        invalidate()
        format = nil
        parameterSets = []
        var description: CMFormatDescription?
        let status = sets.withUnsafeParameterSetPointers { pointers, sizes in
            CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: kCFAllocatorDefault, parameterSetCount: sets.count,
                                                                parameterSetPointers: pointers, parameterSetSizes: sizes,
                                                                nalUnitHeaderLength: 4, formatDescriptionOut: &description)
        }
        guard status == noErr, let description else { return false }
        let specification = [kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: true] as CFDictionary
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
                          kCVPixelBufferMetalCompatibilityKey: true] as CFDictionary
        var created: VTDecompressionSession?
        let made = VTDecompressionSessionCreate(allocator: kCFAllocatorDefault, formatDescription: description,
                                                decoderSpecification: specification, imageBufferAttributes: attributes,
                                                outputCallback: nil, decompressionSessionOut: &created)
        guard made == noErr, let created else { return false }
        VTSessionSetProperty(created, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        session = created
        format = description
        parameterSets = sets
        dimensions = CMVideoFormatDescriptionGetDimensions(description)
        return true
    }

    static func sampleBuffer(_ avcc: Data, format: CMVideoFormatDescription) -> CMSampleBuffer? {
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count,
                                                 blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
                                                 dataLength: avcc.count, flags: kCMBlockBufferAssureMemoryNowFlag,
                                                 blockBufferOut: &block) == noErr, let block else { return nil }
        let copied = avcc.withUnsafeBytes { raw in
            CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count)
        }
        guard copied == noErr else { return nil }
        var sample: CMSampleBuffer?
        var size = avcc.count
        guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
                                        sampleCount: 1, sampleTimingEntryCount: 0, sampleTimingArray: nil,
                                        sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &sample) == noErr else { return nil }
        return sample
    }

    /// A decoded frame wrapped to be shown at once by an AVSampleBufferDisplayLayer.
    static func displayBuffer(_ image: CVImageBuffer) -> CMSampleBuffer? {
        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: image,
                                                           formatDescriptionOut: &format) == noErr, let format else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
                                        decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: image, formatDescription: format,
                                                       sampleTiming: &timing, sampleBufferOut: &sample) == noErr, let sample else { return nil }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true), CFArrayGetCount(attachments) > 0 {
            let dictionary = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dictionary, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }
}

/// What the synchronous output handler hands back.
private final class DecodeResult: @unchecked Sendable {
    var status: OSStatus = noErr
    var image: CVImageBuffer?
}

private extension Array where Element == Data {
    /// Calls `body` with C pointers to each element's bytes and their sizes.
    func withUnsafeParameterSetPointers<R>(_ body: (UnsafePointer<UnsafePointer<UInt8>>, UnsafePointer<Int>) -> R) -> R {
        let buffers = map { data -> UnsafeMutablePointer<UInt8> in
            let pointer = UnsafeMutablePointer<UInt8>.allocate(capacity: Swift.max(data.count, 1))
            data.copyBytes(to: pointer, count: data.count)
            return pointer
        }
        defer { buffers.forEach { $0.deallocate() } }
        let pointers = buffers.map { UnsafePointer($0) }
        let sizes = map(\.count)
        return pointers.withUnsafeBufferPointer { p in sizes.withUnsafeBufferPointer { s in body(p.baseAddress!, s.baseAddress!) } }
    }
}

/// Shows decoded frames at once on an AVSampleBufferDisplayLayer. Safe to call from the stream's queue.
final class ScreenDisplaySink: @unchecked Sendable {
    let layer: AVSampleBufferDisplayLayer

    init(layer: AVSampleBufferDisplayLayer) { self.layer = layer }

    func show(_ image: CVImageBuffer) {
        guard let sample = ScreenVideoDecoder.displayBuffer(image) else { return }
        if #available(macOS 14.0, *) {
            let renderer = layer.sampleBufferRenderer
            if renderer.status == .failed { renderer.flush() }
            renderer.enqueue(sample)
        } else {
            if layer.status == .failed { layer.flush() }
            layer.enqueue(sample)
        }
    }
}
