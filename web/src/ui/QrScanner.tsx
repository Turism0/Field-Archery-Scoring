import { useEffect, useRef, useState } from 'preact/hooks'
import { Dialog } from './common'

/**
 * Scans a QR code with the camera (2.7.06). Uses the browser's own BarcodeDetector where there is one (Chrome on Android),
 * otherwise jsQR (Safari on iPhone has no BarcodeDetector). jsQR is loaded only when the scanner opens. The camera needs
 * https (the site has it) and the person's permission.
 */
export function QrScanner({ open, onResult, onClose, hint }: { open: boolean; onResult: (text: string) => void; onClose: () => void; hint?: string }) {
  const video = useRef<HTMLVideoElement>(null)
  const [error, setError] = useState<string | null>(null)
  const done = useRef(onResult); done.current = onResult

  useEffect(() => {
    if (!open) return
    setError(null)
    let stream: MediaStream | null = null, frame = 0, stopped = false
    const canvas = document.createElement('canvas')
    const ctx = canvas.getContext('2d', { willReadFrequently: true })!
    ;(async () => {
      if (!navigator.mediaDevices?.getUserMedia) { setError("This browser can't use the camera. Type the code instead."); return }
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false })
      } catch (e) {
        const name = (e as DOMException)?.name
        setError(name === 'NotAllowedError' ? 'Camera permission was refused. Allow the camera for this site in the browser settings, or type the code.'
          : name === 'NotFoundError' ? 'No camera found. Type the code instead.' : "Couldn't start the camera. Type the code instead.")
        return
      }
      if (stopped) { stream.getTracks().forEach(t => t.stop()); return }
      const v = video.current!
      v.srcObject = stream; v.setAttribute('playsinline', ''); v.muted = true
      await v.play().catch(() => {})
      const Detector = (window as unknown as { BarcodeDetector?: new (o: { formats: string[] }) => { detect(s: CanvasImageSource): Promise<{ rawValue: string }[]> } }).BarcodeDetector
      let detector: InstanceType<NonNullable<typeof Detector>> | null = null
      if (Detector) { try { detector = new Detector({ formats: ['qr_code'] }) } catch { detector = null } }
      const jsQR = detector ? null : (await import('jsqr')).default
      const tick = async () => {
        if (stopped) return
        if (v.readyState >= 2 && v.videoWidth) {
          let text: string | undefined
          if (detector) {
            try { text = (await detector.detect(v))[0]?.rawValue } catch { /* try the next frame */ }
          } else if (jsQR) {
            const scale = Math.min(1, 640 / Math.max(v.videoWidth, v.videoHeight))
            canvas.width = Math.round(v.videoWidth * scale); canvas.height = Math.round(v.videoHeight * scale)
            ctx.drawImage(v, 0, 0, canvas.width, canvas.height)
            const img = ctx.getImageData(0, 0, canvas.width, canvas.height)
            text = jsQR(img.data, img.width, img.height, { inversionAttempts: 'dontInvert' })?.data
          }
          if (text) { stopped = true; done.current(text); return }
        }
        frame = requestAnimationFrame(tick)
      }
      frame = requestAnimationFrame(tick)
    })()
    return () => { stopped = true; cancelAnimationFrame(frame); stream?.getTracks().forEach(t => t.stop()) }
  }, [open])

  return (
    <Dialog open={open} title="Scan QR code" onClose={onClose} actions={<button onClick={onClose}>Cancel</button>}>
      {error ? <div class="error">{error}</div> : <>
        <div class="scanner"><video ref={video} playsInline muted /><div class="frame" /></div>
        <div class="sub">{hint ?? 'Point the camera at the QR code.'}</div>
      </>}
    </Dialog>
  )
}
