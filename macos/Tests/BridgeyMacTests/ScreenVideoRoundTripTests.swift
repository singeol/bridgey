import AVFoundation
import CoreMedia
import Foundation
import VideoToolbox
import XCTest
@testable import BridgeyMac

/// The decode half of screen sharing with real H.264: VideoToolbox encodes synthetic frames the way a phone
/// would (no B-frames, SPS and PPS before every key frame, Annex B), they are split into screen.frame parts
/// behind the spec header, and the stream receiver reassembles, decodes, shows and acknowledges them.
final class ScreenVideoRoundTripTests: XCTestCase {
    private let streamID = "6c2e9a14-3b5d-4e7f-8a90-1b2c3d4e5f60"

    func testEncodedFramesDecodeWithTheRightSizeAndAreAcknowledged() throws {
        let portrait = try encode(width: 720, height: 1600, frames: 45)
        let landscape = try encode(width: 1600, height: 720, frames: 15)
        XCTAssertTrue(portrait.first?.key == true && landscape.first?.key == true)

        let decoder = ScreenVideoDecoder()
        for (index, unit) in portrait.enumerated() {
            let frame = ScreenAssembledFrame(frame: UInt32(index + 1), key: unit.key, data: unit.annexB)
            guard case let .decoded(image, _) = decoder.decode(frame) else { return XCTFail("frame \(index + 1) did not decode") }
            XCTAssertEqual(CVPixelBufferGetWidth(image), 720)
            XCTAssertEqual(CVPixelBufferGetHeight(image), 1600)
        }

        // The whole receive path: parts, reassembly, decode, display, acks, a rotation.
        let receiver = ScreenStreamReceiver(streamID: streamID)
        receiver.attach(ScreenDisplaySink(layer: AVSampleBufferDisplayLayer()))
        let collector = AckCollector()
        receiver.onAck = { frame, decodeMs, keyframe in collector.acks.append((frame, decodeMs, keyframe)) }
        receiver.onSize = { width, height in collector.sizes.append([width, height]) }
        var sequence = 1
        var frameNumber: UInt32 = 0
        for unit in portrait + landscape {
            frameNumber += 1
            let chunks = stride(from: 0, to: unit.annexB.count, by: 32_768).map { unit.annexB.subdata(in: $0..<min($0 + 32_768, unit.annexB.count)) }
            for (index, chunk) in chunks.enumerated() {
                sequence += 1
                let part = ScreenFramePart(streamID: streamID, sequence: sequence, frame: frameNumber, part: index, key: unit.key,
                                           parameterSets: unit.key, last: index == chunks.count - 1, captureMicros: Int64(frameNumber) * 33_333,
                                           payload: chunk)
                receiver.receive(part)
            }
        }
        receiver.sync()
        let drained = expectation(description: "main queue")
        DispatchQueue.main.async { drained.fulfill() }
        wait(for: [drained], timeout: 5)
        XCTAssertEqual(collector.acks.map(\.0), Array(1...frameNumber), "every frame acknowledged in order")
        XCTAssertFalse(collector.acks.contains { $0.2 }, "no key frame requests")
        XCTAssertEqual(collector.sizes, [[720, 1600], [1600, 720]], "the rotation reaches the window")
    }

    func testALostFrameAsksForAKeyFrameAndRecoversOnTheNext() throws {
        let units = try encode(width: 480, height: 960, frames: 40, keyInterval: 20)
        let receiver = ScreenStreamReceiver(streamID: streamID)
        let collector = AckCollector()
        receiver.onAck = { frame, decodeMs, keyframe in collector.acks.append((frame, decodeMs, keyframe)) }
        var sequence = 1
        for (index, unit) in units.enumerated() {
            sequence += 1
            // Frame 6 arrives without its part 0: lost.
            let part = ScreenFramePart(streamID: streamID, sequence: sequence, frame: UInt32(index + 1), part: index == 5 ? 1 : 0, key: unit.key,
                                       parameterSets: unit.key, last: true, captureMicros: 0, payload: unit.annexB)
            receiver.receive(part)
        }
        receiver.sync()
        let drained = expectation(description: "main queue")
        DispatchQueue.main.async { drained.fulfill() }
        wait(for: [drained], timeout: 5)
        // Frame 6 is lost; the Mac asks for a key frame once and drops 7 to 20 until the key frame 21.
        XCTAssertEqual(collector.acks.first { $0.2 }?.0, 6)
        XCTAssertEqual(collector.acks.map(\.0), Array(1...40), "every frame received is acknowledged, decoded or not")
        XCTAssertEqual(collector.acks.filter { $0.2 }.count, 1, "at most one key frame request a second")
        XCTAssertTrue(units[20].key)
        XCTAssertEqual(collector.acks.last?.0, 40)
    }

    // MARK: Encoding like the phone

    private struct EncodedUnit {
        let key: Bool
        let annexB: Data
    }

    private final class AckCollector: @unchecked Sendable {
        var acks: [(UInt32, Int, Bool)] = []
        var sizes: [[Int]] = []
    }

    private final class SampleCollector: @unchecked Sendable {
        var samples: [CMSampleBuffer] = []
        let lock = NSLock()
    }

    private func encode(width: Int, height: Int, frames: Int, keyInterval: Int = 60) throws -> [EncodedUnit] {
        var created: VTCompressionSession?
        let status = VTCompressionSessionCreate(allocator: nil, width: Int32(width), height: Int32(height), codecType: kCMVideoCodecType_H264,
                                                encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil,
                                                outputCallback: nil, refcon: nil, compressionSessionOut: &created)
        let session = try XCTUnwrap(created, "VTCompressionSessionCreate \(status)")
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Main_AutoLevel)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: keyInterval as CFNumber)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: 4_000_000 as CFNumber)
        let collector = SampleCollector()
        for index in 0..<frames {
            let buffer = try pattern(width: width, height: height, index: index)
            let time = CMTime(value: CMTimeValue(index), timescale: 30)
            VTCompressionSessionEncodeFrame(session, imageBuffer: buffer, presentationTimeStamp: time, duration: CMTime(value: 1, timescale: 30),
                                            frameProperties: nil, infoFlagsOut: nil) { status, _, sample in
                guard status == noErr, let sample else { return }
                collector.lock.withLock { collector.samples.append(sample) }
            }
        }
        VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
        VTCompressionSessionInvalidate(session)
        let samples = collector.lock.withLock { collector.samples }
        XCTAssertEqual(samples.count, frames)
        return samples.map(annexB)
    }

    /// A moving gradient with a bar, so every frame differs.
    private func pattern(width: Int, height: Int, index: Int) throws -> CVPixelBuffer {
        var created: CVPixelBuffer?
        CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32BGRA, [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &created)
        let buffer = try XCTUnwrap(created)
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        let base = try XCTUnwrap(CVPixelBufferGetBaseAddress(buffer)).assumingMemoryBound(to: UInt8.self)
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        let bar = (index * 24) % height
        for y in 0..<height {
            let row = base + y * stride
            for x in 0..<width {
                let pixel = row + x * 4
                let onBar = abs(y - bar) < 40
                pixel[0] = onBar ? 255 : UInt8((x + index * 4) & 0xFF)
                pixel[1] = onBar ? 255 : UInt8((y / 2) & 0xFF)
                pixel[2] = onBar ? 255 : UInt8((x ^ y) & 0xFF)
                pixel[3] = 255
            }
        }
        return buffer
    }

    /// AVCC sample to Annex B, with the SPS and PPS before a key frame (what MediaCodec plus the phone produce).
    private func annexB(_ sample: CMSampleBuffer) -> EncodedUnit {
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]]
        let key = !(attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
        var data = Data()
        let startCode = Data([0, 0, 0, 1])
        if key, let format = CMSampleBufferGetFormatDescription(sample) {
            var count = 0
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil,
                                                               parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
            for index in 0..<count {
                var pointer: UnsafePointer<UInt8>?
                var size = 0
                CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                                                                   parameterSetSizeOut: &size, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
                if let pointer { data.append(startCode); data.append(pointer, count: size) }
            }
        }
        if let block = CMSampleBufferGetDataBuffer(sample) {
            var length = 0
            var pointer: UnsafeMutablePointer<CChar>?
            CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &pointer)
            if let pointer {
                let bytes = Data(bytes: pointer, count: length)
                var offset = 0
                while offset + 4 <= bytes.count {
                    let size = bytes[offset..<offset + 4].reduce(0) { $0 << 8 | Int($1) }
                    offset += 4
                    guard size > 0, offset + size <= bytes.count else { break }
                    data.append(startCode)
                    data.append(bytes[offset..<offset + size])
                    offset += size
                }
            }
        }
        return EncodedUnit(key: key, annexB: data)
    }
}
