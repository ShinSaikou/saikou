#!/usr/bin/env bash
# Phase 1's definition of done (design/plans/phase1-finish.md). The steps are in gate.sh.
exec bash "$(dirname "${BASH_SOURCE[0]}")/gate.sh" 1
