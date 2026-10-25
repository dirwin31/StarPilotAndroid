import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { validDriveStats } from '../app/src/main/assets/demo/web/js/drive-stats.js'
import { validSoftwareSnapshot } from '../app/src/main/assets/demo/web/js/software-status.js'
import { normalizeSnapshot } from '../app/src/main/assets/demo/web/js/system-monitor-data.js'
import { validSentryEvents } from '../app/src/main/assets/demo/web/js/sentry.js'
import { validNotifications } from '../app/src/main/assets/demo/web/js/sentry-notifications.js'
import { validCompanion } from '../app/src/main/assets/demo/web/js/companion.js'
import { driveSummary } from '../app/src/main/assets/demo/web/js/home.js'
const fixture = path => JSON.parse(readFileSync(new URL(`../app/src/main/assets/demo/api/${path}.json`, import.meta.url)))
assert.ok(validDriveStats(fixture('drives/stats')))
assert.ok(driveSummary({ status: 'ready', data: fixture('drives/stats') }).week.days.length === 7)
assert.ok(validSoftwareSnapshot(fixture('software/status')))
assert.ok(normalizeSnapshot(fixture('system/monitor'), 'local-runtime'))
assert.ok(validSentryEvents(fixture('sentry/events')))
assert.ok(validNotifications(fixture('sentry/notifications')))
assert.ok(validCompanion(fixture('companion/status')))
console.log('Demo fixtures satisfy the shipped Galaxy UI contracts')
