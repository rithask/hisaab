# Brand

**Logo:** a copper *hole pice*, the Indian one-pice coin of the 1940s with a hole through the middle (copper, beaded
rim), on a midnight-blue tile. It stands for money kept whole and in order; *hisaab* means "accounts".

| Use | File |
|---|---|
| Icon (rounded tile) | `hisaab-icon.svg`, `frontend/public/icon.svg` |
| Icon, full bleed (iOS and Android cut their own shape) | `frontend/public/icon-maskable.svg` |
| Lockup with wordmark, on light / dark | `hisaab-lockup-light.svg`, `hisaab-lockup-dark.svg` |
| App PNGs | `frontend/public/icon-192.png`, `icon-512.png`, `icon-maskable-512.png` (512, full bleed), `apple-touch-icon.png` (180, full bleed), `favicon-32.png` |

**Colours**
- Tile: midnight blue, a diagonal from `#16213e` (top left) to `#0b1020` (bottom right). The hole is `#0b1020`.
- Copper: radial from `#f0a775` (highlight, upper left) to `#a4521f`. Rim and beads: `#7a3a14` at 35–50% opacity.
- Wordmark: Manrope ExtraBold, lowercase *hisaab*, tracking −1.3 at 44 px, as outlines. Ink `#0b1020`, or white on dark.

**Rules**
- The coin stays whole and centred; it sits inside Android's safe circle, so the full-bleed version needs no inset.
- Don't recolour the copper or add a letter to the coin. Don't use the Lion Capital emblem or other official
  symbols of India (their use is restricted by law).
- When the SVGs change, re-render every PNG at the sizes above from `icon.svg` (192, 512, 32) and
  `icon-maskable.svg` (512, 180), with a transparent background outside the tile.
