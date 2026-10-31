#!/usr/bin/env bash
# Phase 0's definition of done (contract 01, section 9). The steps are in gate.sh.
exec bash "$(dirname "${BASH_SOURCE[0]}")/gate.sh" 0
