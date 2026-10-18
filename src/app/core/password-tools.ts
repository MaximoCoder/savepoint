export interface GeneratorOptions {
  length: number;
  digits: boolean;
  symbols: boolean;
}

// Sin caracteres ambiguos (I, l, O, 0, 1) para poder leerla y teclearla si hace falta
const LOWER = 'abcdefghijkmnopqrstuvwxyz';
const UPPER = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
const DIGITS = '23456789';
const SYMBOLS = '!@#$%&*-_=+?';

/** Entero uniforme en [0, max) usando el generador criptográfico, sin sesgo de módulo. */
function randomInt(max: number): number {
  const limit = Math.floor(0x1_0000_0000 / max) * max;
  const buf = new Uint32Array(1);
  let x: number;
  do {
    crypto.getRandomValues(buf);
    x = buf[0];
  } while (x >= limit);
  return x % max;
}

function pick(set: string): string {
  return set[randomInt(set.length)];
}

export function generatePassword(opts: GeneratorOptions): string {
  const sets = [LOWER, UPPER];
  if (opts.digits) sets.push(DIGITS);
  if (opts.symbols) sets.push(SYMBOLS);
  const all = sets.join('');
  const length = Math.max(opts.length, sets.length);

  // Garantiza al menos un carácter de cada grupo elegido
  const chars = sets.map(pick);
  while (chars.length < length) chars.push(pick(all));

  // Fisher-Yates con aleatoriedad criptográfica
  for (let i = chars.length - 1; i > 0; i--) {
    const j = randomInt(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}

export interface Strength {
  score: 0 | 1 | 2 | 3 | 4;
  label: string;
}

const LABELS = ['Muy débil', 'Débil', 'Aceptable', 'Fuerte', 'Muy fuerte'];

/** Estimación simple por entropía. Orientativa, no sustituye un análisis serio. */
export function passwordStrength(pw: string): Strength {
  if (!pw) return { score: 0, label: '' };
  let pool = 0;
  if (/[a-z]/.test(pw)) pool += 26;
  if (/[A-Z]/.test(pw)) pool += 26;
  if (/\d/.test(pw)) pool += 10;
  if (/[^A-Za-z0-9]/.test(pw)) pool += 33;
  const bits = pw.length * Math.log2(Math.max(pool, 2));
  const score = (bits < 40 ? 0 : bits < 60 ? 1 : bits < 80 ? 2 : bits < 100 ? 3 : 4) as Strength['score'];
  return { score, label: LABELS[score] };
}

/** Quita acentos y pasa a minúsculas para que la búsqueda sea tolerante. */
export function normalize(text: string): string {
  return text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();
}

/** Nombre de dominio legible a partir de lo que el usuario escribió en "sitio". */
export function displayHost(url: string): string {
  if (!url) return '';
  try {
    const withScheme = /^[a-z]+:\/\//i.test(url) ? url : `https://${url}`;
    return new URL(withScheme).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}
