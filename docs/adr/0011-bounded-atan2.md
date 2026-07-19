# ADR 0011: Deterministic finite-coordinate atan2

Status: accepted

Kotoba floating policy v6 admits `f64-atan2-bounded` only for two finite
binary64 coordinates. NaN and either infinity trap. The operation preserves
all signed-zero quadrant cases, including `atan2(-0,+x) = -0` and
`atan2(-0,-x) = -pi`.

The implementation reduces absolute coordinates into a ratio in `[0,1]`,
selects the octant explicitly, and evaluates a fixed degree-39 odd atan kernel.
For ratios above `sqrt(2)-1`, it uses the fixed `pi/4 + atan((r-1)/(r+1))`
transform. Coefficient bits and evaluation order are sealed; FMA and host
`atan2` imports are forbidden. Reference, restricted JavaScript, and typed
Wasm must remain within `2e-15` absolute error and agree on signed-zero and
quadrant vectors before this policy may ship.
