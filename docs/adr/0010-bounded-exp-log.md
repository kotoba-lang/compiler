# ADR 0010: Bounded exponential and logarithm

Status: accepted

## Decision

The floating policy advances to
`:kotoba.floating-point/ieee-754-f32-f64-v5`. `f64-exp-near-zero` accepts
finite `[-0.5,0.5]`; `f64-log-near-one` accepts finite `[0.75,1.5]`.

Exponential uses a fixed degree-18 Taylor Horner kernel. Logarithm uses
`y=(x-1)/(x+1)` and a fixed degree-21 odd atanh-series Horner kernel. Both
publish a conservative `4e-15` absolute-error bound, forbid FMA, and trap on
non-finite or out-of-domain input.

Reference, restricted JavaScript, and Wasm execute the same binary64
coefficient bits and operation order. No backend calls or imports a host
exponential or logarithm function. Wider scaling and exponent extraction are
future separately qualified operations.
