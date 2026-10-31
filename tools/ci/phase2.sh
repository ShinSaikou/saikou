#!/usr/bin/env bash
# Phase 2's definition of done (design/plans/phase2-start.md). The steps are in gate.sh.
exec bash "$(dirname "${BASH_SOURCE[0]}")/gate.sh" 2
