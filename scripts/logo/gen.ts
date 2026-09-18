// `pnpm logo` - regenerate every shipped rendition of the realmdo mark from
// one source.
//
// The mark is the Realm mark (../holden/scripts/logo/gen.ts) with a checkmark
// where Realm has its R: the same lavender squircle tile, the same clay filter
// chain, a cream check raised on it, and a dark twin (indigo tile, lavender
// check). The check is drawn here as a filled outline (a two-segment stroke
// with round caps and a round join), so nothing loads a font.
//
// Writes:
//   web/public/logo.svg, logo-dark.svg            the mark, 400x400 viewBox
//   web/public/favicon.svg                        both, picked by prefers-color-scheme
//   web/public/favicon.ico                        16/32/48 from the light mark
//   android/.../mipmap-*/ic_launcher_{background,foreground}.png
//                                                 the adaptive icon's two layers, per density
//   android/.../drawable/ic_launcher_monochrome.xml, ic_stat_task.xml
//                                                 the check outline as vector drawables
//   android/.../drawable-*/mark.png, drawable-night-*/mark.png
//                                                 the mark at 28dp for the app's title bar, per density
//
// Needs `rsvg-convert` and `magick` on PATH (`brew install librsvg imagemagick`).

const ROOT = new URL("../../", import.meta.url).pathname;
const WEB_PUBLIC = `${ROOT}web/public/`;
const ANDROID_RES = `${ROOT}android/app/src/main/res/`;

// ---- colors -----------------------------------------------------------------

type Mode = { tile: string; mark: string; dark: boolean };
const LIGHT: Mode = { tile: "#b4a3f2", mark: "#fdf3e4", dark: false };
const DARK: Mode = { tile: "#2c2450", mark: "#b4a3f2", dark: true };

function mix(hex: string, to: string, t: number): string {
  const n = parseInt(hex.slice(1), 16), m = parseInt(to.slice(1), 16);
  const c = (sh: number) =>
    Math.round(((n >> sh) & 255) + (((m >> sh) & 255) - ((n >> sh) & 255)) * t)
      .toString(16)
      .padStart(2, "0");
  return "#" + c(16) + c(8) + c(0);
}
const tint = (h: string) => mix(h, "#ffffff", 0.32);
const shade = (h: string) => mix(h, "#1a1030", 0.55);

// ---- geometry ---------------------------------------------------------------

// Superellipse centred at 200,200 in the 400-unit mark space; half-size a,
// exponent n.
function squircle(a: number, n: number, steps = 240): string {
  const pts: string[] = [];
  for (let i = 0; i < steps; i++) {
    const t = (i / steps) * Math.PI * 2;
    const c = Math.cos(t), s = Math.sin(t);
    const x = 200 + a * Math.sign(c) * Math.abs(c) ** (2 / n);
    const y = 200 + a * Math.sign(s) * Math.abs(s) ** (2 / n);
    pts.push(`${x.toFixed(1)} ${y.toFixed(1)}`);
  }
  return `M ${pts.join(" L ")} Z`;
}

const TILE_PATH = squircle(160, 4.5); // spans 40..360

// ---- the check --------------------------------------------------------------

type Pt = [number, number];

// The check's spine in mark space: short stroke down-right, long stroke up-right.
// Its ink spans about the 52% of the tile that Realm's R does.
const CHECK_SPINE: [Pt, Pt, Pt] = [[118, 208], [178, 268], [284, 132]];
const CHECK_WIDTH = 52;

const unit = (a: Pt, b: Pt): Pt => {
  const dx = b[0] - a[0], dy = b[1] - a[1], l = Math.hypot(dx, dy);
  return [dx / l, dy / l];
};
const left = (d: Pt): Pt => [-d[1], d[0]];
const add = (p: Pt, d: Pt, k: number): Pt => [p[0] + d[0] * k, p[1] + d[1] * k];
const angle = (d: Pt) => Math.atan2(d[1], d[0]);

// Points on a circle from angle `from`, sweeping `by` radians.
function arc(c: Pt, r: number, from: number, by: number, steps = 24): Pt[] {
  const pts: Pt[] = [];
  for (let i = 0; i <= steps; i++) {
    const t = from + (by * i) / steps;
    pts.push([c[0] + r * Math.cos(t), c[1] + r * Math.sin(t)]);
  }
  return pts;
}

// Outline of the stroked spine: round caps at both ends, a round join on the
// outside of the corner, the two edges meeting in a point on the inside.
function checkOutline(): string {
  const [a, b, c] = CHECK_SPINE;
  const r = CHECK_WIDTH / 2;
  const d1 = unit(a, b), d2 = unit(b, c);
  const n1 = left(d1), n2 = left(d2); // the outside of the corner, at the bottom
  let turn = angle(n2) - angle(n1);
  while (turn > Math.PI) turn -= 2 * Math.PI;
  while (turn < -Math.PI) turn += 2 * Math.PI;
  // The inside edges are the spine offset by -r; they cross at b pushed along
  // the corner's bisector.
  const half = Math.abs(turn) / 2;
  const bis: Pt = [-(n1[0] + n2[0]), -(n1[1] + n2[1])];
  const bl = Math.hypot(bis[0], bis[1]);
  const inner = add(b, [bis[0] / bl, bis[1] / bl], r / Math.cos(half));
  const pts: Pt[] = [
    add(a, n1, r),
    ...arc(b, r, angle(n1), turn),
    ...arc(c, r, angle(n2), -Math.PI),
    inner,
    ...arc(a, r, angle([-n1[0], -n1[1]]), -Math.PI),
  ];
  return `M ${
    pts.map((p) => `${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(" L ")
  } Z`;
}

const CHECK_PATH = checkOutline();
// The check's ink height in mark units.
const CHECK_INK = Math.max(...CHECK_SPINE.map((p) => p[1])) -
  Math.min(...CHECK_SPINE.map((p) => p[1])) + CHECK_WIDTH;

// ---- clay -------------------------------------------------------------------

type Clay = {
  id: string;
  d: number;
  shadow: string;
  lightOp?: number;
  darkOp?: number;
  dropOp?: number;
};

function clay(o: Clay): string {
  const { id, d, shadow } = o;
  const lightOp = o.lightOp ?? 0.85,
    darkOp = o.darkOp ?? 0.45,
    dropOp = o.dropOp ?? 0.28;
  return `<filter id="${id}" x="-45%" y="-45%" width="190%" height="190%" color-interpolation-filters="sRGB">
<feGaussianBlur in="SourceAlpha" stdDeviation="${
    Math.round(d * 1.5)
  }" result="dropBlur"/>
<feOffset in="dropBlur" dx="${d}" dy="${
    Math.round(d * 1.75)
  }" result="dropOff"/>
<feFlood flood-color="${shadow}" flood-opacity="${dropOp}" result="dropColor"/>
<feComposite in="dropColor" in2="dropOff" operator="in" result="drop"/>
<feOffset in="SourceAlpha" dx="${d}" dy="${d}" result="oL"/>
<feGaussianBlur in="oL" stdDeviation="${d}" result="bL"/>
<feComposite in="SourceAlpha" in2="bL" operator="out" result="rimL"/>
<feFlood flood-color="#ffffff" flood-opacity="${lightOp}" result="fL"/>
<feComposite in="fL" in2="rimL" operator="in" result="light"/>
<feOffset in="SourceAlpha" dx="-${d}" dy="-${d}" result="oD"/>
<feGaussianBlur in="oD" stdDeviation="${d}" result="bD"/>
<feComposite in="SourceAlpha" in2="bD" operator="out" result="rimD"/>
<feFlood flood-color="${shadow}" flood-opacity="${darkOp}" result="fD"/>
<feComposite in="fD" in2="rimD" operator="in" result="dark"/>
<feOffset in="SourceAlpha" dx="2" dy="2" result="oS"/>
<feGaussianBlur in="oS" stdDeviation="1.2" result="bS"/>
<feComposite in="SourceAlpha" in2="bS" operator="out" result="rimS"/>
<feFlood flood-color="#ffffff" flood-opacity="0.9" result="fS"/>
<feComposite in="fS" in2="rimS" operator="in" result="spec"/>
<feMerge><feMergeNode in="drop"/><feMergeNode in="SourceGraphic"/><feMergeNode in="light"/><feMergeNode in="dark"/><feMergeNode in="spec"/></feMerge>
</filter>`;
}

const grad = (id: string, a: string, b: string) =>
  `<linearGradient id="${id}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient>`;

// ---- the mark, in its 400-unit space ----------------------------------------

type Mark = { defs: string; tile: string; check: string };

// `p` prefixes the ids so two marks can share one document.
function mark(m: Mode, p = ""): Mark {
  const defs = grad(
    `${p}gTile`,
    m.dark ? mix(m.tile, "#ffffff", 0.18) : tint(m.tile),
    m.tile,
  ) +
    grad(`${p}gCheck`, tint(m.mark), m.mark) +
    (m.dark
      ? clay({
        id: `${p}fTile`,
        d: 14,
        shadow: "#0d0a1f",
        lightOp: 0.35,
        darkOp: 0.6,
        dropOp: 0.45,
      })
      : clay({ id: `${p}fTile`, d: 12, shadow: shade(m.tile) })) +
    clay({ id: `${p}fCheck`, d: 7, shadow: shade(m.mark), dropOp: 0.3 });
  const tile =
    `<path d="${TILE_PATH}" fill="url(#${p}gTile)" filter="url(#${p}fTile)"/>`;
  const check =
    `<path d="${CHECK_PATH}" fill="url(#${p}gCheck)" filter="url(#${p}fCheck)"/>`;
  return { defs, tile, check };
}

const svgOpen = (viewBox: string) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}" role="img" aria-label="realmdo">\n<title>realmdo</title>`;

function logoSvg(m: Mark, desc: string): string {
  return `${
    svgOpen("0 0 400 400")
  }\n<desc>${desc}</desc>\n<defs>${m.defs}</defs>\n<g>${m.tile}${m.check}</g>\n</svg>\n`;
}

// Cropped so the tile fills the frame, for icon sizes.
const FAVICON_BOX = "32 32 336 336";

// Both marks; the page's color scheme picks one.
function faviconSvg(light: Mark, dark: Mark): string {
  return `${svgOpen(FAVICON_BOX)}
<style>.dark{display:none}@media (prefers-color-scheme:dark){.light{display:none}.dark{display:inline}}</style>
<defs>${light.defs}${dark.defs}</defs>
<g class="light">${light.tile}${light.check}</g>
<g class="dark">${dark.tile}${dark.check}</g>
</svg>
`;
}

// One mark at the favicon crop: the .ico frames, which cannot follow the
// color scheme, and the Android title-bar PNGs, where the resource qualifier
// picks the twin.
function icoSvg(m: Mark): string {
  return `${
    svgOpen(FAVICON_BOX)
  }\n<defs>${m.defs}</defs>\n<g>${m.tile}${m.check}</g>\n</svg>\n`;
}

// ---- the Android adaptive icon ----------------------------------------------

// Mark space onto the 108dp adaptive canvas: the 320-unit tile spans the 72dp
// the mask shows.
const TO_ICON = `translate(54 54) scale(${72 / 320}) translate(-200 -200)`;
const MARK_DP = 28;
const DENSITIES: Record<string, number> = {
  mdpi: 108,
  hdpi: 162,
  xhdpi: 216,
  xxhdpi: 324,
  xxxhdpi: 432,
};

function layerSvg(m: Mark, body: string, floor?: string): string {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">
<defs>${m.defs}</defs>
${floor ? `<rect width="108" height="108" fill="${floor}"/>` : ""}
<g transform="${TO_ICON}">${body}</g>
</svg>
`;
}

// The check alone as a vector drawable: `height` dp tall, the mark's centre on
// the centre of a `size` dp canvas.
function vectorDrawable(size: number, height: number, comment: string): string {
  const k = height / CHECK_INK;
  const t = (size / 2 - k * 200).toFixed(4);
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- ${comment} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${size}dp"
    android:height="${size}dp"
    android:viewportWidth="${size}"
    android:viewportHeight="${size}">
    <group
        android:scaleX="${k.toFixed(6)}"
        android:scaleY="${k.toFixed(6)}"
        android:translateX="${t}"
        android:translateY="${t}">
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="${CHECK_PATH}" />
    </group>
</vector>
`;
}

// ---- rasterising ------------------------------------------------------------

async function run(cmd: string, args: string[], stdin?: string): Promise<void> {
  const p = new Deno.Command(cmd, {
    args,
    stdin: stdin === undefined ? "null" : "piped",
    stdout: "null",
    stderr: "piped",
  });
  const child = p.spawn();
  if (stdin !== undefined) {
    const w = child.stdin.getWriter();
    await w.write(new TextEncoder().encode(stdin));
    await w.close();
  }
  const out = await child.output();
  if (!out.success) {
    throw new Error(
      `${cmd} ${args.join(" ")}: ${new TextDecoder().decode(out.stderr)}`,
    );
  }
}

const png = (svg: string, px: number, out: string) =>
  run("rsvg-convert", ["-w", `${px}`, "-h", `${px}`, "-o", out], svg);

async function ensureTools(): Promise<void> {
  for (const t of ["rsvg-convert", "magick"]) {
    try {
      await run(t, ["--version"]);
    } catch {
      console.error(`${t} is not on PATH: brew install librsvg imagemagick`);
      Deno.exit(1);
    }
  }
}

// ---- main -------------------------------------------------------------------

await ensureTools();
const light = mark(LIGHT), dark = mark(DARK);
const written: string[] = [];
const write = async (path: string, text: string) => {
  await Deno.writeTextFile(path, text);
  written.push(path.slice(ROOT.length));
};

await Deno.mkdir(WEB_PUBLIC, { recursive: true });
await write(WEB_PUBLIC + "logo.svg", logoSvg(light, "The realmdo mark, light"));
await write(
  WEB_PUBLIC + "logo-dark.svg",
  logoSvg(dark, "The realmdo mark, dark"),
);
await write(
  WEB_PUBLIC + "favicon.svg",
  faviconSvg(mark(LIGHT, "l"), mark(DARK, "d")),
);

const tmp = await Deno.makeTempDir({ prefix: "realmdo-logo-" });
try {
  const icoFrames: string[] = [];
  for (const px of [16, 32, 48]) {
    const f = `${tmp}/${px}.png`;
    await png(icoSvg(light), px, f);
    icoFrames.push(f);
  }
  await run("magick", [...icoFrames, WEB_PUBLIC + "favicon.ico"]);
  written.push("web/public/favicon.ico");

  const floor = mix(LIGHT.tile, "#1a1030", 0.12);
  const background = layerSvg(light, light.tile, floor);
  const foreground = layerSvg(light, light.check);
  for (const [name, px] of Object.entries(DENSITIES)) {
    const dir = `${ANDROID_RES}mipmap-${name}/`;
    await Deno.mkdir(dir, { recursive: true });
    await png(background, px, dir + "ic_launcher_background.png");
    await png(foreground, px, dir + "ic_launcher_foreground.png");
    written.push(
      `android/.../mipmap-${name}/ic_launcher_{background,foreground}.png`,
    );
    // The title-bar mark, 28dp on the favicon crop; the dark twin under night.
    const markPx = Math.round(MARK_DP * (px / 108));
    for (
      const [qual, m] of [
        [`drawable-${name}`, light],
        [`drawable-night-${name}`, dark],
      ] as const
    ) {
      await Deno.mkdir(ANDROID_RES + qual, { recursive: true });
      await png(icoSvg(m), markPx, `${ANDROID_RES}${qual}/mark.png`);
    }
    written.push(`android/.../drawable{,-night}-${name}/mark.png`);
  }
} finally {
  await Deno.remove(tmp, { recursive: true });
}

// The check at the size it has when the tile fills the 72dp the mask shows.
const CHECK_DP = 72 * (CHECK_INK / 320);
(CHECK_SPINE[2][1] - CHECK_SPINE[0][1] + CHECK_WIDTH) / 320;
await write(
  ANDROID_RES + "drawable/ic_launcher_monochrome.xml",
  vectorDrawable(
    108,
    CHECK_DP,
    `The themed-icon layer: the launcher flattens it to alpha and tints it, so
     this is the mark's check alone, the outline web/public/logo.svg carries,
     at the size it has on the tile (the tile fills the 72dp the mask shows).
     Generated by scripts/logo/gen.ts, like the mipmap PNGs, from the same
     path.`,
  ),
);
await write(
  ANDROID_RES + "drawable/ic_stat_task.xml",
  vectorDrawable(
    24,
    17,
    `The notification's action icon. Flattened to its alpha channel and tinted
     by the system, so this is the mark's check alone, the same outline as
     @drawable/ic_launcher_monochrome, 17dp tall on the 24dp canvas. The small
     icon says the task's kind instead (ic_stat_once, ic_stat_interval,
     ic_stat_slot). Generated by scripts/logo/gen.ts.`,
  ),
);

console.log(written.map((w) => `  ${w}`).join("\n"));
