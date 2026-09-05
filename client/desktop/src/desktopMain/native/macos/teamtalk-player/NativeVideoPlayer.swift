/*
 * TeamTalk local-file macOS video backend.
 *
 * Derived from ComposeMediaPlayer's macOS NativeVideoPlayer implementation:
 * https://github.com/kdroidFilter/ComposeMediaPlayer
 * Copyright (c) 2025 Elie G.
 * SPDX-License-Identifier: MIT
 *
 * This narrow implementation deliberately removes the upstream network/HLS and
 * unstructured Task/loadTracks code paths. Every AVPlayer mutation is serialized
 * per handle, and disposal synchronously detaches every owned native resource.
 */

import AVFoundation
import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation

private enum LocalVideoSourceError: Error, CustomStringConvertible {
    case invalidSource(String)
    case unavailableFile(String)
    case unsupportedVideo(String)
    case disposed

    var description: String {
        switch self {
        case .invalidSource(let reason):
            return reason
        case .unavailableFile(let reason):
            return reason
        case .unsupportedVideo(let reason):
            return reason
        case .disposed:
            return "Video player is already disposed"
        }
    }

    var statusCode: Int32 {
        switch self {
        case .invalidSource, .unavailableFile:
            return 1
        case .unsupportedVideo, .disposed:
            return 2
        }
    }
}

/**
 * One native player handle with a single serialized AVFoundation owner.
 *
 * There are intentionally no Swift Tasks, loadTracks completion handlers,
 * timers, KVO observations, or NotificationCenter callbacks in this class.
 * Kotlin already polls position and frames, so adding a second asynchronous
 * lifecycle here only creates work that dispose cannot deterministically join.
 */
private final class TeamTalkMacVideoPlayer {
    private let stateQueue = DispatchQueue(label: "com.virjar.teamtalk.video.local-player")
    private let stateQueueKey = DispatchSpecificKey<UInt8>()
    private let stateQueueValue: UInt8 = 1

    // Frame readers return a native pointer to Kotlin and release it in a later JNI call.
    // unlockLatestFrame deliberately bypasses stateQueue so synchronous dispose can wait
    // for an already-admitted reader without deadlocking behind itself.
    private let frameCondition = NSCondition()
    private var latestPixelBuffer: CVPixelBuffer?
    private var lockedPixelBuffer: CVPixelBuffer?

    private var player: AVPlayer?
    private var playerItem: AVPlayerItem?
    private var videoOutput: AVPlayerItemVideoOutput?
    private var asset: AVURLAsset?
    private var disposed = false

    private var nativeVideoWidth = 0
    private var nativeVideoHeight = 0
    private var frameWidth = 0
    private var frameHeight = 0
    private var videoFrameRate: Float = 30
    private var screenRefreshRate: Float = 60
    private var captureFrameRate: Float = 30
    private var durationSeconds: Double = 0

    private var volume: Float = 1
    private var playbackSpeed: Float = 1
    private var isPlaying = false
    private var playbackWasStarted = false
    private var didReportPlaybackEnd = false

    private var videoTitle: String?
    private var videoBitrate: Int64 = 0
    private var videoMimeType: String?
    private var audioChannels = 0
    private var audioSampleRate = 0

    init() {
        stateQueue.setSpecific(key: stateQueueKey, value: stateQueueValue)
        screenRefreshRate = Self.detectScreenRefreshRate()
        captureFrameRate = min(screenRefreshRate, videoFrameRate)
    }

    private func serialized<T>(_ operation: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: stateQueueKey) == stateQueueValue {
            return try autoreleasepool(invoking: operation)
        }
        return try stateQueue.sync {
            try autoreleasepool(invoking: operation)
        }
    }

    func openLocalSource(_ source: String) throws {
        let localURL = try Self.resolveLocalFile(source)
        try serialized {
            guard !disposed else { throw LocalVideoSourceError.disposed }

            // Construct the replacement completely before publishing it to player state.
            // The synchronous legacy accessors are intentional: for an already-complete local
            // file they avoid callbacks that could retain AVAsset after dispose has returned.
            let replacementAsset = AVURLAsset(
                url: localURL,
                options: [AVURLAssetPreferPreciseDurationAndTimingKey: true]
            )
            let videoTrack = replacementAsset.tracks(withMediaType: .video).first
            let audioTrack = replacementAsset.tracks(withMediaType: .audio).first
            guard videoTrack != nil || audioTrack != nil else {
                replacementAsset.cancelLoading()
                throw LocalVideoSourceError.unsupportedVideo("Local file has no readable audio or video track")
            }

            // ComposeMediaPlayer 0.9 polls width/height for every media kind before it marks
            // open complete. Audio-only files therefore expose a metadata-only 1x1 sentinel;
            // they still have no AVPlayerItemVideoOutput and lockLatestFrame always returns nil.
            var width = 1
            var height = 1
            if let videoTrack {
                let naturalSize = videoTrack.naturalSize.applying(videoTrack.preferredTransform)
                width = Int(abs(naturalSize.width).rounded())
                height = Int(abs(naturalSize.height).rounded())
                guard width > 0, height > 0 else {
                    replacementAsset.cancelLoading()
                    throw LocalVideoSourceError.unsupportedVideo("Local video has invalid dimensions")
                }
            }

            let rawDuration = CMTimeGetSeconds(replacementAsset.duration)
            guard rawDuration.isFinite, rawDuration > 0 else {
                replacementAsset.cancelLoading()
                throw LocalVideoSourceError.unsupportedVideo("Local video has no finite duration")
            }

            let output = videoTrack.map { _ in Self.makeVideoOutput(width: width, height: height) }
            let item = AVPlayerItem(asset: replacementAsset)
            if let output {
                item.add(output)
            }
            let replacementPlayer = AVPlayer(playerItem: item)
            replacementPlayer.actionAtItemEnd = .pause
            replacementPlayer.automaticallyWaitsToMinimizeStalling = false
            replacementPlayer.volume = volume
            replacementPlayer.defaultRate = playbackSpeed

            cleanupCurrentMediaLocked()

            asset = replacementAsset
            playerItem = item
            videoOutput = output
            player = replacementPlayer
            nativeVideoWidth = videoTrack == nil ? 0 : width
            nativeVideoHeight = videoTrack == nil ? 0 : height
            frameWidth = width
            frameHeight = height
            durationSeconds = rawDuration

            if let videoTrack {
                let nominalRate = videoTrack.nominalFrameRate
                videoFrameRate = nominalRate.isFinite && nominalRate > 0 ? nominalRate : 30
                captureFrameRate = max(1, min(screenRefreshRate, videoFrameRate))
            } else {
                videoFrameRate = 0
                captureFrameRate = 0
            }
            readMetadataLocked(
                url: localURL,
                videoTrack: videoTrack,
                audioTrack: audioTrack
            )

            isPlaying = false
            playbackWasStarted = false
            didReportPlaybackEnd = false
            replacementPlayer.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero)
        }
    }

    func play() {
        serialized {
            guard !disposed, let player else { return }
            playbackWasStarted = true
            didReportPlaybackEnd = false
            isPlaying = true
            player.playImmediately(atRate: playbackSpeed)
        }
    }

    func pause() {
        serialized {
            guard !disposed else { return }
            player?.pause()
            isPlaying = false
            captureLatestFrameLocked()
        }
    }

    func seek(to seconds: Double) {
        serialized {
            guard !disposed, let player, let item = playerItem else { return }
            let upperBound = durationSeconds.isFinite ? durationSeconds : max(0, seconds)
            let targetSeconds = min(max(0, seconds), upperBound)
            let target = CMTime(seconds: targetSeconds, preferredTimescale: 600)
            item.cancelPendingSeeks()
            clearLatestFrameLocked(waitForReader: true)
            didReportPlaybackEnd = false
            player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
            if isPlaying {
                player.playImmediately(atRate: playbackSpeed)
            }
        }
    }

    func setVolume(_ requestedVolume: Float) {
        serialized {
            volume = min(max(0, requestedVolume), 1)
            player?.volume = volume
        }
    }

    func getVolume() -> Float {
        serialized { volume }
    }

    func setPlaybackSpeed(_ requestedSpeed: Float) {
        serialized {
            playbackSpeed = min(max(0.25, requestedSpeed), 2)
            player?.defaultRate = playbackSpeed
            if isPlaying {
                player?.rate = playbackSpeed
            }
        }
    }

    func getPlaybackSpeed() -> Float {
        serialized { playbackSpeed }
    }

    func lockLatestFrame(_ outInfo: UnsafeMutablePointer<Int32>) -> UnsafeMutableRawPointer? {
        serialized {
            guard !disposed else { return nil }
            captureLatestFrameLocked()

            frameCondition.lock()
            defer { frameCondition.unlock() }
            guard lockedPixelBuffer == nil, let pixelBuffer = latestPixelBuffer else { return nil }

            let lockStatus = CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
            guard lockStatus == kCVReturnSuccess,
                  let address = CVPixelBufferGetBaseAddress(pixelBuffer) else {
                if lockStatus == kCVReturnSuccess {
                    CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly)
                }
                return nil
            }

            lockedPixelBuffer = pixelBuffer
            outInfo[0] = Int32(CVPixelBufferGetWidth(pixelBuffer))
            outInfo[1] = Int32(CVPixelBufferGetHeight(pixelBuffer))
            outInfo[2] = Int32(CVPixelBufferGetBytesPerRow(pixelBuffer))
            return address
        }
    }

    func unlockLatestFrame() {
        frameCondition.lock()
        if let pixelBuffer = lockedPixelBuffer {
            CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly)
            lockedPixelBuffer = nil
            frameCondition.broadcast()
        }
        frameCondition.unlock()
    }

    func getFrameWidth() -> Int32 {
        serialized { Int32(frameWidth) }
    }

    func getFrameHeight() -> Int32 {
        serialized { Int32(frameHeight) }
    }

    func setOutputSize(width requestedWidth: Int32, height requestedHeight: Int32) -> Int32 {
        serialized {
            guard !disposed,
                  requestedWidth > 0,
                  requestedHeight > 0,
                  nativeVideoWidth > 0,
                  nativeVideoHeight > 0,
                  let item = playerItem else { return 0 }

            let scaleX = Double(requestedWidth) / Double(nativeVideoWidth)
            let scaleY = Double(requestedHeight) / Double(nativeVideoHeight)
            let scale = min(scaleX, scaleY, 1)
            let newWidth = max(2, (Int(Double(nativeVideoWidth) * scale) / 2) * 2)
            let newHeight = max(2, (Int(Double(nativeVideoHeight) * scale) / 2) * 2)
            guard newWidth != frameWidth || newHeight != frameHeight else { return 0 }

            clearLatestFrameLocked(waitForReader: true)
            if let oldOutput = videoOutput {
                item.remove(oldOutput)
            }
            let replacement = Self.makeVideoOutput(width: newWidth, height: newHeight)
            item.add(replacement)
            videoOutput = replacement
            frameWidth = newWidth
            frameHeight = newHeight
            return 1
        }
    }

    func getVideoFrameRate() -> Float {
        serialized { videoFrameRate }
    }

    func getScreenRefreshRate() -> Float {
        serialized { screenRefreshRate }
    }

    func getCaptureFrameRate() -> Float {
        serialized { captureFrameRate }
    }

    func getDuration() -> Double {
        serialized { durationSeconds }
    }

    func getCurrentTime() -> Double {
        serialized {
            guard !disposed, let item = playerItem else { return 0 }
            let value = CMTimeGetSeconds(item.currentTime())
            return value.isFinite && value >= 0 ? value : 0
        }
    }

    func getVideoTitle() -> String? {
        serialized { videoTitle }
    }

    func getVideoBitrate() -> Int64 {
        serialized { videoBitrate }
    }

    func getVideoMimeType() -> String? {
        serialized { videoMimeType }
    }

    func getAudioChannels() -> Int32 {
        serialized { Int32(audioChannels) }
    }

    func getAudioSampleRate() -> Int32 {
        serialized { Int32(audioSampleRate) }
    }

    func consumeDidPlayToEnd() -> Int32 {
        serialized {
            guard !disposed,
                  playbackWasStarted,
                  !didReportPlaybackEnd,
                  durationSeconds > 0,
                  let item = playerItem else { return 0 }
            let current = CMTimeGetSeconds(item.currentTime())
            let ended = current.isFinite && current >= max(0, durationSeconds - 0.05) && player?.rate == 0
            guard ended else { return 0 }
            didReportPlaybackEnd = true
            isPlaying = false
            return 1
        }
    }

    /** Returns only after all resources owned by this handle have been detached and released. */
    func disposeSynchronously() {
        serialized {
            guard !disposed else { return }
            disposed = true
            cleanupCurrentMediaLocked()
        }
    }

    private func cleanupCurrentMediaLocked() {
        isPlaying = false
        player?.pause()
        player?.cancelPendingPrerolls()
        playerItem?.cancelPendingSeeks()

        // A Kotlin frame copy admitted before disposal owns the returned pointer until
        // nUnlockFrame. Waiting here makes nDisposePlayer a real lifetime barrier.
        clearLatestFrameLocked(waitForReader: true)

        if let item = playerItem, let output = videoOutput {
            item.remove(output)
        }
        videoOutput = nil
        player?.replaceCurrentItem(with: nil)
        playerItem = nil
        asset?.cancelLoading()
        asset = nil
        player = nil

        nativeVideoWidth = 0
        nativeVideoHeight = 0
        frameWidth = 0
        frameHeight = 0
        durationSeconds = 0
        videoTitle = nil
        videoBitrate = 0
        videoMimeType = nil
        audioChannels = 0
        audioSampleRate = 0
        playbackWasStarted = false
        didReportPlaybackEnd = false
    }

    private func captureLatestFrameLocked() {
        guard let item = playerItem, let output = videoOutput else { return }
        let itemTime = item.currentTime()
        guard output.hasNewPixelBuffer(forItemTime: itemTime),
              let pixelBuffer = output.copyPixelBuffer(forItemTime: itemTime, itemTimeForDisplay: nil) else {
            return
        }
        frameCondition.lock()
        latestPixelBuffer = pixelBuffer
        frameCondition.unlock()
    }

    private func clearLatestFrameLocked(waitForReader: Bool) {
        frameCondition.lock()
        if waitForReader {
            while lockedPixelBuffer != nil {
                frameCondition.wait()
            }
        }
        latestPixelBuffer = nil
        frameCondition.unlock()
    }

    private func readMetadataLocked(
        url: URL,
        videoTrack: AVAssetTrack?,
        audioTrack: AVAssetTrack?
    ) {
        videoTitle = url.deletingPathExtension().lastPathComponent
        videoMimeType = Self.mimeType(for: url)

        let estimatedRate = videoTrack?.estimatedDataRate ?? audioTrack?.estimatedDataRate ?? 0
        if estimatedRate.isFinite && estimatedRate > 0 {
            videoBitrate = Int64(estimatedRate)
        } else if let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
                  let byteCount = attributes[.size] as? NSNumber,
                  durationSeconds > 0 {
            videoBitrate = Int64((byteCount.doubleValue * 8) / durationSeconds)
        }

        if let audioTrack {
            for rawDescription in audioTrack.formatDescriptions {
                let description = rawDescription as! CMAudioFormatDescription
                guard let stream = CMAudioFormatDescriptionGetStreamBasicDescription(description) else {
                    continue
                }
                audioChannels = Int(stream.pointee.mChannelsPerFrame)
                audioSampleRate = Int(stream.pointee.mSampleRate)
                break
            }
        }
    }

    private static func resolveLocalFile(_ source: String) throws -> URL {
        guard !source.isEmpty, !source.contains("\0") else {
            throw LocalVideoSourceError.invalidSource("Video source must be a non-empty local file")
        }

        let resolved: URL
        if let parsed = URL(string: source), let scheme = parsed.scheme {
            guard scheme.lowercased() == "file", parsed.isFileURL else {
                throw LocalVideoSourceError.invalidSource("Only complete local file URIs are supported")
            }
            let host = parsed.host?.lowercased()
            guard host == nil || host == "" || host == "localhost" else {
                throw LocalVideoSourceError.invalidSource("Remote file URI authorities are not supported")
            }
            guard parsed.path.hasPrefix("/") else {
                throw LocalVideoSourceError.invalidSource("Local file URI must contain an absolute path")
            }
            resolved = parsed.standardizedFileURL
        } else {
            // ComposeMediaPlayer 0.9's public API passes PlatformFile as an absolute POSIX
            // path. Normalize that compatibility form into a complete file URL here; relative
            // paths remain rejected and no network form is ever accepted.
            guard source.hasPrefix("/") else {
                throw LocalVideoSourceError.invalidSource("Local video path must be absolute")
            }
            resolved = URL(fileURLWithPath: source, isDirectory: false).standardizedFileURL
        }

        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: resolved.path, isDirectory: &isDirectory),
              !isDirectory.boolValue else {
            throw LocalVideoSourceError.unavailableFile("Local video file does not exist")
        }
        guard FileManager.default.isReadableFile(atPath: resolved.path) else {
            throw LocalVideoSourceError.unavailableFile("Local video file is not readable")
        }
        return resolved
    }

    private static func makeVideoOutput(width: Int, height: Int) -> AVPlayerItemVideoOutput {
        AVPlayerItemVideoOutput(pixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        ])
    }

    private static func mimeType(for url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "aac":
            return "audio/aac"
        case "m4a":
            return "audio/mp4"
        case "mp3":
            return "audio/mpeg"
        case "wav":
            return "audio/wav"
        case "mov":
            return "video/quicktime"
        case "m4v":
            return "video/x-m4v"
        case "webm":
            return "video/webm"
        default:
            return "video/mp4"
        }
    }

    private static func detectScreenRefreshRate() -> Float {
        var displayLink: CVDisplayLink?
        guard CVDisplayLinkCreateWithCGDisplay(CGMainDisplayID(), &displayLink) == kCVReturnSuccess,
              let displayLink else { return 60 }
        let period = CVDisplayLinkGetNominalOutputVideoRefreshPeriod(displayLink)
        guard period.timeValue > 0, period.timeScale > 0 else { return 60 }
        let rate = Float(period.timeScale) / Float(period.timeValue)
        return rate.isFinite && rate > 0 ? rate : 60
    }
}

// MARK: - C exports consumed by jni_bridge.c

@_cdecl("createVideoPlayer")
public func createVideoPlayer() -> UnsafeMutableRawPointer? {
    Unmanaged.passRetained(TeamTalkMacVideoPlayer()).toOpaque()
}

@_cdecl("openLocalFile")
public func openLocalFile(
    _ context: UnsafeMutableRawPointer?,
    _ uri: UnsafePointer<CChar>?,
    _ errorOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    guard let context, let uri, let source = String(validatingUTF8: uri) else {
        errorOut?.pointee = strdup("Invalid local video parameters")
        return 1
    }
    let player = Unmanaged<TeamTalkMacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    do {
        try player.openLocalSource(source)
        return 0
    } catch let error as LocalVideoSourceError {
        errorOut?.pointee = strdup(error.description)
        return error.statusCode
    } catch {
        errorOut?.pointee = strdup("Unable to open local video")
        return 2
    }
}

@_cdecl("playVideo")
public func playVideo(_ context: UnsafeMutableRawPointer?) {
    withPlayer(context) { $0.play() }
}

@_cdecl("pauseVideo")
public func pauseVideo(_ context: UnsafeMutableRawPointer?) {
    withPlayer(context) { $0.pause() }
}

@_cdecl("setVolume")
public func setVolume(_ context: UnsafeMutableRawPointer?, _ volume: Float) {
    withPlayer(context) { $0.setVolume(volume) }
}

@_cdecl("getVolume")
public func getVolume(_ context: UnsafeMutableRawPointer?) -> Float {
    withPlayer(context, default: 0) { $0.getVolume() }
}

@_cdecl("seekTo")
public func seekTo(_ context: UnsafeMutableRawPointer?, _ time: Double) {
    withPlayer(context) { $0.seek(to: time) }
}

@_cdecl("disposeVideoPlayer")
public func disposeVideoPlayer(_ context: UnsafeMutableRawPointer?) {
    guard let context else { return }
    let player = Unmanaged<TeamTalkMacVideoPlayer>.fromOpaque(context).takeRetainedValue()
    player.disposeSynchronously()
}

@_cdecl("setPlaybackSpeed")
public func setPlaybackSpeed(_ context: UnsafeMutableRawPointer?, _ speed: Float) {
    withPlayer(context) { $0.setPlaybackSpeed(speed) }
}

@_cdecl("getPlaybackSpeed")
public func getPlaybackSpeed(_ context: UnsafeMutableRawPointer?) -> Float {
    withPlayer(context, default: 1) { $0.getPlaybackSpeed() }
}

@_cdecl("lockLatestFrame")
public func lockLatestFrame(
    _ context: UnsafeMutableRawPointer?,
    _ outInfo: UnsafeMutablePointer<Int32>?
) -> UnsafeMutableRawPointer? {
    guard let context, let outInfo else { return nil }
    return Unmanaged<TeamTalkMacVideoPlayer>.fromOpaque(context)
        .takeUnretainedValue()
        .lockLatestFrame(outInfo)
}

@_cdecl("unlockLatestFrame")
public func unlockLatestFrame(_ context: UnsafeMutableRawPointer?) {
    withPlayer(context) { $0.unlockLatestFrame() }
}

@_cdecl("getFrameWidth")
public func getFrameWidth(_ context: UnsafeMutableRawPointer?) -> Int32 {
    withPlayer(context, default: 0) { $0.getFrameWidth() }
}

@_cdecl("getFrameHeight")
public func getFrameHeight(_ context: UnsafeMutableRawPointer?) -> Int32 {
    withPlayer(context, default: 0) { $0.getFrameHeight() }
}

@_cdecl("setOutputSize")
public func setOutputSize(
    _ context: UnsafeMutableRawPointer?,
    _ width: Int32,
    _ height: Int32
) -> Int32 {
    withPlayer(context, default: 0) { $0.setOutputSize(width: width, height: height) }
}

@_cdecl("getVideoFrameRate")
public func getVideoFrameRate(_ context: UnsafeMutableRawPointer?) -> Float {
    withPlayer(context, default: 0) { $0.getVideoFrameRate() }
}

@_cdecl("getScreenRefreshRate")
public func getScreenRefreshRate(_ context: UnsafeMutableRawPointer?) -> Float {
    withPlayer(context, default: 0) { $0.getScreenRefreshRate() }
}

@_cdecl("getCaptureFrameRate")
public func getCaptureFrameRate(_ context: UnsafeMutableRawPointer?) -> Float {
    withPlayer(context, default: 0) { $0.getCaptureFrameRate() }
}

@_cdecl("getVideoDuration")
public func getVideoDuration(_ context: UnsafeMutableRawPointer?) -> Double {
    withPlayer(context, default: 0) { $0.getDuration() }
}

@_cdecl("getCurrentTime")
public func getCurrentTime(_ context: UnsafeMutableRawPointer?) -> Double {
    withPlayer(context, default: 0) { $0.getCurrentTime() }
}

@_cdecl("getVideoTitle")
public func getVideoTitle(_ context: UnsafeMutableRawPointer?) -> UnsafePointer<CChar>? {
    guard let value = withPlayer(context, default: nil, operation: { $0.getVideoTitle() }) else { return nil }
    return UnsafePointer(strdup(value))
}

@_cdecl("getVideoBitrate")
public func getVideoBitrate(_ context: UnsafeMutableRawPointer?) -> Int64 {
    withPlayer(context, default: 0) { $0.getVideoBitrate() }
}

@_cdecl("getVideoMimeType")
public func getVideoMimeType(_ context: UnsafeMutableRawPointer?) -> UnsafePointer<CChar>? {
    guard let value = withPlayer(context, default: nil, operation: { $0.getVideoMimeType() }) else { return nil }
    return UnsafePointer(strdup(value))
}

@_cdecl("getAudioChannels")
public func getAudioChannels(_ context: UnsafeMutableRawPointer?) -> Int32 {
    withPlayer(context, default: 0) { $0.getAudioChannels() }
}

@_cdecl("getAudioSampleRate")
public func getAudioSampleRate(_ context: UnsafeMutableRawPointer?) -> Int32 {
    withPlayer(context, default: 0) { $0.getAudioSampleRate() }
}

@_cdecl("consumeDidPlayToEnd")
public func consumeDidPlayToEnd(_ context: UnsafeMutableRawPointer?) -> Int32 {
    withPlayer(context, default: 0) { $0.consumeDidPlayToEnd() }
}

private func withPlayer(
    _ context: UnsafeMutableRawPointer?,
    operation: (TeamTalkMacVideoPlayer) -> Void
) {
    guard let context else { return }
    operation(Unmanaged<TeamTalkMacVideoPlayer>.fromOpaque(context).takeUnretainedValue())
}

private func withPlayer<T>(
    _ context: UnsafeMutableRawPointer?,
    default defaultValue: T,
    operation: (TeamTalkMacVideoPlayer) -> T
) -> T {
    guard let context else { return defaultValue }
    return operation(Unmanaged<TeamTalkMacVideoPlayer>.fromOpaque(context).takeUnretainedValue())
}
