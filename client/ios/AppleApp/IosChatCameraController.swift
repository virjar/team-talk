import AVFoundation
import AVKit
import UIKit

/// 应用内聊天相机：点按拍照、长按录像（松手结束，60 秒自动停），拍摄即震动。
/// 拍摄瞬间按设备物理方向（UIDevice.orientation）写入方向元数据，不受界面旋转锁定影响
/// （内测反馈：锁定竖屏横持拍摄的视频必须宽大于高）。拍完后停在预览确认层（重拍/发送）。
/// 由 Kotlin 侧经 IosApplicationRuntime.openChatCamera 触发呈现，结果回调文件路径。
final class IosChatCameraController: UIViewController {
    /// (文件 URL, 是否照片)；nil 表示取消或失败。
    var onResult: ((URL?, Bool) -> Void)?

    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "teamtalk.chat.camera")
    private let photoOutput = AVCapturePhotoOutput()
    private let movieOutput = AVCaptureMovieFileOutput()
    private let previewLayer = AVCaptureVideoPreviewLayer()
    private var videoInput: AVCaptureDeviceInput?
    private var audioInput: AVCaptureDeviceInput?
    private var position: AVCaptureDevice.Position = .back
    /// 最近一次的物理方向；faceUp/Down 等不更新，拍摄瞬间作为元数据写入。
    private var lastVideoOrientation: AVCaptureVideoOrientation = .portrait
    private var recordingURL: URL?
    private var countdownTimer: Timer?
    private var recordStartedAt: Date?
    private var pendingPhotoURL: URL?
    private var pendingVideoURL: URL?
    private var player: AVPlayer?
    private var confirming = false

    private let shutter = UIControl()
    private let shutterRing = UIView()
    private let shutterCore = UIView()
    private let flipButton = UIButton(type: .system)
    private let timerLabel = UILabel()
    private let cancelButton = UIButton(type: .system)
    private let retakeButton = UIButton(type: .system)
    private let sendButton = UIButton(type: .system)
    private let countdownTotal: TimeInterval = 60

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        buildUI()
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        NotificationCenter.default.addObserver(
            self, selector: #selector(orientationChanged),
            name: UIDevice.orientationDidChangeNotification, object: nil)
        orientationChanged()
        ensurePermissionAndStart()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer.frame = view.bounds
    }

    // MARK: 权限与会话

    private func ensurePermissionAndStart() {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] videoGranted in
            guard let self, videoGranted else {
                DispatchQueue.main.async { self?.failWithAlert("未授予相机权限，无法拍摄") }
                return
            }
            AVCaptureDevice.requestAccess(for: .audio) { _ in
                self.sessionQueue.async { self.configureSession() }
            }
        }
    }

    private func configureSession() {
        session.beginConfiguration()
        session.sessionPreset = .high
        addVideoInput(position: .back)
        // 模拟器/无摄像头设备没有视频输入：capturePhoto 会直接抛异常，必须提前拦截。
        guard videoInput != nil else {
            session.commitConfiguration()
            DispatchQueue.main.async { [weak self] in
                self?.failWithAlert("此设备没有可用的摄像头")
            }
            return
        }
        if let microphone = AVCaptureDevice.default(for: .audio),
            let input = try? AVCaptureDeviceInput(device: microphone),
            session.canAddInput(input) {
            session.addInput(input)
            audioInput = input
        }
        if session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }
        if session.canAddOutput(movieOutput) { session.addOutput(movieOutput) }
        movieOutput.maxRecordedDuration = CMTime(seconds: countdownTotal, preferredTimescale: 600)
        session.commitConfiguration()
        session.startRunning()
    }

    private func addVideoInput(position: AVCaptureDevice.Position) {
        if let existing = videoInput {
            session.removeInput(existing)
            videoInput = nil
        }
        guard
            let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
                ?? AVCaptureDevice.default(for: .video),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else { return }
        session.addInput(input)
        videoInput = input
        self.position = position
    }

    private func flipCamera() {
        guard !confirming, !movieOutput.isRecording else { return }
        let next: AVCaptureDevice.Position = position == .back ? .front : .back
        sessionQueue.async {
            self.session.beginConfiguration()
            self.addVideoInput(position: next)
            self.session.commitConfiguration()
        }
    }

    // MARK: 方向

    @objc private func orientationChanged() {
        let mapped: AVCaptureVideoOrientation
        switch UIDevice.current.orientation {
        case .portrait: mapped = .portrait
        case .portraitUpsideDown: mapped = .portraitUpsideDown
        case .landscapeLeft: mapped = .landscapeLeft
        case .landscapeRight: mapped = .landscapeRight
        default: return
        }
        lastVideoOrientation = mapped
    }

    /// 拍摄瞬间把物理方向写到输出连接上；方向元数据随文件保存。
    private func applyOrientation() {
        for output in [photoOutput, movieOutput] as [AVCaptureOutput] {
            if let connection = output.connection(with: .video), connection.isVideoOrientationSupported {
                connection.videoOrientation = lastVideoOrientation
            }
        }
    }

    // MARK: 拍摄

    private func takePhoto() {
        guard !confirming, !movieOutput.isRecording else { return }
        // 无视频连接（无摄像头设备）时 capturePhoto 会抛 ObjC 异常。
        guard photoOutput.connection(with: .video) != nil else {
            failWithAlert("此设备没有可用的摄像头")
            return
        }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        applyOrientation()
        photoOutput.capturePhoto(
            with: AVCapturePhotoSettings(),
            delegate: self)
    }

    private func startRecording() {
        guard !confirming, !movieOutput.isRecording else { return }
        guard movieOutput.connection(with: .video) != nil else {
            failWithAlert("此设备没有可用的摄像头")
            return
        }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        applyOrientation()
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("capture-\(UUID().uuidString).mov")
        recordingURL = url
        movieOutput.startRecording(to: url, recordingDelegate: self)
        recordStartedAt = Date()
        shutterCore.backgroundColor = .systemRed
        countdownTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            guard let self, let start = self.recordStartedAt else { return }
            let elapsed = Date().timeIntervalSince(start)
            self.timerLabel.text = String(format: "%.1f s", min(elapsed, self.countdownTotal))
        }
    }

    private func stopRecording() {
        guard movieOutput.isRecording else { return }
        countdownTimer?.invalidate()
        countdownTimer = nil
        shutterCore.backgroundColor = .white
        movieOutput.stopRecording()
    }

    private func finishCapture(url: URL, isImage: Bool) {
        confirming = true
        session.stopRunning()
        showConfirm(url: url, isImage: isImage)
    }

    // MARK: 确认层

    private func showConfirm(url: URL, isImage: Bool) {
        let overlay = UIView(frame: view.bounds)
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        overlay.backgroundColor = .black
        overlay.tag = 900
        if isImage, let image = UIImage(contentsOfFile: url.path) {
            let imageView = UIImageView(image: image)
            imageView.frame = overlay.bounds
            imageView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            imageView.contentMode = .scaleAspectFit
            overlay.addSubview(imageView)
        } else if !isImage {
            let player = AVPlayer(url: url)
            player.actionAtItemEnd = .none
            player.isMuted = false
            player.play()
            NotificationCenter.default.addObserver(
                self, selector: #selector(videoLooped(_:)),
                name: .AVPlayerItemDidPlayToEndTime, object: player.currentItem)
            let layer = AVPlayerLayer(player: player)
            layer.frame = overlay.bounds
            layer.videoGravity = .resizeAspect
            overlay.layer.addSublayer(layer)
            self.player = player
        }
        view.addSubview(overlay)
        view.bringSubviewToFront(retakeButton)
        view.bringSubviewToFront(sendButton)
        retakeButton.isHidden = false
        sendButton.isHidden = false
        shutter.isHidden = true
        flipButton.isHidden = true
        timerLabel.isHidden = true
    }

    @objc private func videoLooped(_ notification: Notification) {
        (notification.object as? AVPlayerItem)?.seek(
            to: CMTime.zero,
            toleranceBefore: .zero, toleranceAfter: .zero)
    }

    private func clearConfirm() {
        player?.pause()
        player = nil
        view.subviews.first(where: { $0.tag == 900 })?.removeFromSuperview()
        retakeButton.isHidden = true
        sendButton.isHidden = true
        shutter.isHidden = false
        flipButton.isHidden = false
        timerLabel.isHidden = false
        confirming = false
        sessionQueue.async { self.session.startRunning() }
    }

    private func failWithAlert(_ message: String) {
        let alert = UIAlertController(title: "操作未完成", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "确定", style: .default) { [weak self] _ in
            self?.onResult?(nil, false)
            self?.dismiss(animated: true)
        })
        present(alert, animated: true)
    }

    // MARK: 界面

    private func buildUI() {
        previewLayer.session = session
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.frame = view.bounds
        view.layer.addSublayer(previewLayer)

        cancelButton.setTitle("取消", for: .normal)
        cancelButton.setTitleColor(.white, for: .normal)
        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        cancelButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(cancelButton)

        flipButton.setImage(
            UIImage(systemName: "arrow.triangle.2.circlepath.camera"), for: .normal)
        flipButton.tintColor = .white
        flipButton.addTarget(self, action: #selector(flipTapped), for: .touchUpInside)
        flipButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(flipButton)

        timerLabel.textColor = .white
        timerLabel.font = .monospacedDigitSystemFont(ofSize: 16, weight: .medium)
        timerLabel.text = "0.0 s"
        timerLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(timerLabel)

        shutterRing.backgroundColor = .clear
        shutterRing.layer.borderWidth = 4
        shutterRing.layer.borderColor = UIColor.white.cgColor
        shutterRing.layer.cornerRadius = 39
        shutterRing.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(shutterRing)

        shutterCore.backgroundColor = .white
        shutterCore.layer.cornerRadius = 31
        shutterCore.translatesAutoresizingMaskIntoConstraints = false
        shutterRing.addSubview(shutterCore)

        let tap = UITapGestureRecognizer(target: self, action: #selector(shutterTapped))
        shutterRing.addGestureRecognizer(tap)
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(shutterLongPressed(_:)))
        longPress.minimumPressDuration = 0.25
        shutterRing.addGestureRecognizer(longPress)

        retakeButton.setTitle("重拍", for: .normal)
        retakeButton.setTitleColor(.white, for: .normal)
        retakeButton.isHidden = true
        retakeButton.addTarget(self, action: #selector(retakeTapped), for: .touchUpInside)
        retakeButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(retakeButton)

        sendButton.setTitle("发送", for: .normal)
        sendButton.setTitleColor(.white, for: .normal)
        sendButton.backgroundColor = .systemBlue
        sendButton.layer.cornerRadius = 8
        sendButton.contentEdgeInsets = UIEdgeInsets(top: 8, left: 20, bottom: 8, right: 20)
        sendButton.isHidden = true
        sendButton.addTarget(self, action: #selector(sendTapped), for: .touchUpInside)
        sendButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(sendButton)

        NSLayoutConstraint.activate([
            cancelButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            cancelButton.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
            flipButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            flipButton.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
            timerLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            timerLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            shutterRing.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            shutterRing.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -36),
            shutterRing.widthAnchor.constraint(equalToConstant: 78),
            shutterRing.heightAnchor.constraint(equalToConstant: 78),
            shutterCore.centerXAnchor.constraint(equalTo: shutterRing.centerXAnchor),
            shutterCore.centerYAnchor.constraint(equalTo: shutterRing.centerYAnchor),
            shutterCore.widthAnchor.constraint(equalToConstant: 62),
            shutterCore.heightAnchor.constraint(equalToConstant: 62),
            retakeButton.centerYAnchor.constraint(equalTo: sendButton.centerYAnchor),
            retakeButton.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 32),
            sendButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -32),
            sendButton.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -32),
        ])
    }

    @objc private func cancelTapped() {
        onResult?(nil, false)
        dismiss(animated: true)
    }

    @objc private func flipTapped() { flipCamera() }

    @objc private func shutterTapped() { takePhoto() }

    @objc private func shutterLongPressed(_ gesture: UILongPressGestureRecognizer) {
        switch gesture.state {
        case .began: startRecording()
        case .ended, .cancelled, .failed: stopRecording()
        default: break
        }
    }

    @objc private func retakeTapped() {
        if let photo = pendingPhotoURL {
            try? FileManager.default.removeItem(at: photo)
            pendingPhotoURL = nil
        }
        if let video = pendingVideoURL {
            try? FileManager.default.removeItem(at: video)
            pendingVideoURL = nil
        }
        clearConfirm()
    }

    @objc private func sendTapped() {
        if let photo = pendingPhotoURL {
            onResult?(photo, true)
        } else if let video = pendingVideoURL {
            onResult?(video, false)
        }
        dismiss(animated: true)
    }
}

extension IosChatCameraController: AVCapturePhotoCaptureDelegate {
    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        guard error == nil, let data = photo.fileDataRepresentation() else { return }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("capture-\(UUID().uuidString).jpg")
        do {
            try data.write(to: url)
        } catch { return }
        DispatchQueue.main.async { [weak self] in
            self?.pendingPhotoURL = url
            self?.finishCapture(url: url, isImage: true)
        }
    }
}

extension IosChatCameraController: AVCaptureFileOutputRecordingDelegate {
    func fileOutput(
        _ output: AVCaptureFileOutput,
        didStartRecordingTo fileURL: URL,
        from connections: [AVCaptureConnection]
    ) {}

    func fileOutput(
        _ output: AVCaptureFileOutput,
        didFinishRecordingTo outputFileURL: URL,
        from connections: [AVCaptureConnection],
        error: Error?
    ) {
        // 达到 maxRecordedDuration 时系统带错误回调，但文件完整可用。
        let usable = error == nil || (error as? AVError)?.code == .maximumDurationReached
        guard usable, FileManager.default.fileExists(atPath: outputFileURL.path) else {
            try? FileManager.default.removeItem(at: outputFileURL)
            return
        }
        DispatchQueue.main.async { [weak self] in
            self?.pendingVideoURL = outputFileURL
            self?.finishCapture(url: outputFileURL, isImage: false)
        }
    }
}
