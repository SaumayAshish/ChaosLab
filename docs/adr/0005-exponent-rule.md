# 0005. A Lyapunov exponent is claimed only under a stated rule

Status: accepted

## Context
A finite-time estimate of a Lyapunov exponent over a short window or a regular orbit looks like a positive number. Early versions
reported 0.0049 per day for the regular Sun-Earth system.

## Decision
`ExponentRule` returns NO_DATA, PROVISIONAL, SLOWING or CLAIMED. An exponent is claimed only when at least 10 renormalisations exist
and the slope over the later part of the run is at least 0.75 of the running mean. Otherwise the window says why none is claimed.
The exponent is the slope of the cumulative log growth over a window, not total over T, and is shown with the reference's own energy
drift.

## Evidence
Claims per samples at 10 to 90 renormalisations, threshold 0.75: regular systems circular two-body 0/41, eccentric two-body 1/41,
Sun-Earth 0/41, figure-eight 0/41, Sun-Earth-Moon 2/41; chaotic systems Lagrange displaced 12/26, Lagrange exact 11/11,
Pythagorean 12/16. At the earlier threshold of 0.5, Sun-Earth-Moon was wrongly claimed in 24 of 41 samples. A second criterion
(running mean not falling) did not remove the false claims and was dropped.

## Consequences
The rule is a screen, not a proof of regularity: 3 of 205 regular samples still pass. The window words it that way.
