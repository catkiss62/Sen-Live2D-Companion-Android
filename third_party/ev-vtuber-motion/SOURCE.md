# E.V VTuber motion-layer source

This test branch contains an Android port of the motion-only performance layer bundled in:

- Repository: <https://github.com/siaoic/E.V>
- Source subtree: `cortico-world-vtuber-main/`
- Reference commit: `473a3563d91cd4708dd3683d0cff0dc6f522fe52`
- Upstream license: GNU Affero General Public License v3.0 or later

The following assets are copied byte-for-byte from that commit so the faithful mode can execute
the same motion curves instead of retyping them:

- `app/src/main/assets/ev-vtuber-pack/clips.json`
- `app/src/main/assets/ev-vtuber-pack/vocab.json`

`EvFaithfulMotionEngine.java` ports the motion-related algorithms and constants from upstream
`src/mixer.ts` and `src/clips.ts`. `SenNaturalMotionEngine.java` is a separate Sen-specific
experimental implementation. The original `SenPerformanceEngine.java` remains independent.

The complete upstream license text is preserved in
`third_party/ev-vtuber-motion/LICENSE-AGPL-3.0-or-later.txt`.
