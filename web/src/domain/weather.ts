// Weather for a round (3.2.17, Pete). The server looks it up from Open-Meteo using the shoot location's map pin and the
// round's date and times (the phone's position is never sent), and phones download it. Same as the app's
// domain/Weather.kt. Tested in test/analysis.test.ts.

/** Averages over the round: temperature °C, wind km/h, the strongest gust km/h, rain mm in total, WMO weather code. */
export interface Weather {
  tempC: number | null
  windKph: number | null
  gustKph: number | null
  rainMm: number | null
  code: number | null
}

export const kphToMph = (kph: number) => kph / 1.609344

/** A simple description of a WMO weather code (the worst hour of the round). */
export function condition(code: number | null): string | null {
  if (code == null) return null
  if (code === 0) return 'Sunny'
  if (code <= 2) return 'Partly cloudy'
  if (code === 3) return 'Cloudy'
  if (code === 45 || code === 48) return 'Fog'
  if (code >= 51 && code <= 57) return 'Drizzle'
  if (code >= 61 && code <= 67) return 'Rain'
  if (code >= 71 && code <= 77) return 'Snow'
  if (code >= 80 && code <= 82) return 'Showers'
  if (code === 85 || code === 86) return 'Snow showers'
  if (code >= 95) return 'Thunder'
  return null
}

/** A small symbol for history lists. */
export function weatherIcon(code: number | null): string {
  const c = condition(code)
  return c === 'Sunny' ? '☀️' : c === 'Partly cloudy' ? '⛅' : c === 'Cloudy' ? '☁️' : c === 'Fog' ? '🌫️'
    : c === 'Drizzle' || c === 'Rain' || c === 'Showers' ? '🌧️' : c === 'Snow' || c === 'Snow showers' ? '❄️' : c === 'Thunder' ? '⛈️' : ''
}

/** "12°C · Rain, 3.2 mm · wind 14 mph, gusts 25 mph" (parts that are missing are left out). */
export function weatherLine(w: Weather): string {
  const parts: string[] = []
  if (w.tempC != null) parts.push(`${Math.round(w.tempC)}°C`)
  const c = condition(w.code)
  if (c) parts.push(w.rainMm != null && w.rainMm >= 0.1 ? `${c}, ${w.rainMm.toFixed(1)} mm` : c)
  else if (w.rainMm != null && w.rainMm >= 0.1) parts.push(`${w.rainMm.toFixed(1)} mm rain`)
  if (w.windKph != null) {
    const gust = w.gustKph != null ? `, gusts ${Math.round(kphToMph(w.gustKph))} mph` : ''
    parts.push(`wind ${Math.round(kphToMph(w.windKph))} mph${gust}`)
  }
  return parts.join(' · ')
}

/** Wet: at least 0.5 mm of rain over the round, or drizzle, rain, showers or thunder. */
export function isWet(w: Weather): boolean {
  const c = condition(w.code)
  return (w.rainMm != null && w.rainMm >= 0.5) || c === 'Drizzle' || c === 'Rain' || c === 'Showers' || c === 'Thunder'
}

/** Windy: an average wind of 15 mph (24 km/h) or more. */
export const WINDY_KPH = 24
export const isWindy = (w: Weather) => w.windKph != null && w.windKph >= WINDY_KPH

/** Cold: under 8°C on average. */
export const COLD_C = 8
export const isCold = (w: Weather) => w.tempC != null && w.tempC < COLD_C

export function weatherFromJson(j: any): Weather | null {
  if (!j) return null
  const n = (v: any) => (typeof v === 'number' ? v : null)
  const w = { tempC: n(j.tempC), windKph: n(j.windKph), gustKph: n(j.gustKph), rainMm: n(j.rainMm), code: n(j.code) }
  return Object.values(w).every(v => v == null) ? null : w
}
