const PATTERNS = {
  classic: { frequencies: [880, 660], onMs: 220, gapMs: 110, cycleMs: 900 },
  gentle: { frequencies: [523.25, 659.25, 783.99], onMs: 280, gapMs: 100, cycleMs: 1_200 },
  pulse: { frequencies: [1_046.5], onMs: 160, gapMs: 90, cycleMs: 620 },
  chime: { frequencies: [659.25, 783.99, 1_046.5], onMs: 230, gapMs: 90, cycleMs: 1_350 },
  digital: { frequencies: [1_046.5, 1_046.5], onMs: 90, gapMs: 80, cycleMs: 560 }
};

const VIBRATION = {
  none: [],
  standard: [180, 120, 180, 420],
  pulse: [90, 80, 90, 80, 90, 360]
};

export class AlarmAudio {
  constructor() {
    this.context = null;
    this.timer = null;
    this.vibrationTimer = null;
    this.settings = null;
    this.startedAt = 0;
  }

  async ensureContext() {
    this.context ??= new AudioContext();
    if (this.context.state === "suspended") await this.context.resume();
  }

  async playPulse(frequency, durationMs, volume) {
    await this.ensureContext();
    const oscillator = this.context.createOscillator();
    const gain = this.context.createGain();
    const now = this.context.currentTime;
    oscillator.type = "sine";
    oscillator.frequency.value = frequency;
    gain.gain.setValueAtTime(0.0001, now);
    gain.gain.exponentialRampToValueAtTime(Math.max(0.0001, volume), now + 0.015);
    gain.gain.exponentialRampToValueAtTime(0.0001, now + durationMs / 1000);
    oscillator.connect(gain).connect(this.context.destination);
    oscillator.start(now);
    oscillator.stop(now + durationMs / 1000 + 0.02);
  }

  async start(settings) {
    this.stop();
    this.settings = settings;
    this.startedAt = Date.now();
    const pattern = PATTERNS[settings.sound] ?? PATTERNS.classic;
    const tick = async () => {
      const elapsed = Date.now() - this.startedAt;
      const ramp = settings.gradualVolume ? Math.min(1, elapsed / 30_000) : 1;
      const volume = Math.max(0.01, settings.volume * Math.max(0.1, ramp));
      for (let index = 0; index < pattern.frequencies.length; index += 1) {
        await new Promise((resolve) => setTimeout(resolve, index * (pattern.onMs + pattern.gapMs)));
        if (this.timer === null) return;
        await this.playPulse(pattern.frequencies[index], pattern.onMs, volume);
      }
    };
    this.timer = setInterval(() => { void tick(); }, pattern.cycleMs);
    void tick();
    if (settings.vibration && navigator.vibrate) {
      const vibration = VIBRATION[settings.vibrationPattern] ?? VIBRATION.standard;
      this.vibrationTimer = setInterval(() => navigator.vibrate(vibration), 1_200);
      navigator.vibrate(vibration);
    }
  }

  async preview(settings) {
    await this.start({ ...settings, gradualVolume: false });
    window.setTimeout(() => this.stop(), 3_500);
  }

  stop() {
    if (this.timer !== null) window.clearInterval(this.timer);
    if (this.vibrationTimer !== null) window.clearInterval(this.vibrationTimer);
    this.timer = null;
    this.vibrationTimer = null;
    if (navigator.vibrate) navigator.vibrate(0);
  }
}
