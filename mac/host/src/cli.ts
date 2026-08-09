#!/usr/bin/env node
import { PROTOCOL_MAJOR } from "@pimobile/protocol";

process.stdout.write(`Pi Mobile host protocol ${String(PROTOCOL_MAJOR)}\n`);
