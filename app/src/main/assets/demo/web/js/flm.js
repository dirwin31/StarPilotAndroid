import { GxState } from "./state.js"
import { GxNotice } from "./notice.js"
import { PollTimer, connectionError } from "./polling.js"
import { requestJson } from "./startup.js"
import { PlotGraph, PlotsFeed, chartGeometry } from "./plots.js"
import { validTrainingResults, trainingSummary, readable, detailText, surfaceRows, coordinateText, trackingView, measurementText, validTrackingValues, trackingPlotView, sourceSeconds } from "./flm-training-results.js"

export const FlmPage = {
  name: "FlmPage",
  components: { GxState, GxNotice, PlotGraph },
  props: { mode: { type: String, required: true }, unauthorized: { type: Function, required: true }, embedded: { type: Boolean, default: false } },
  data: () => ({ report: null, error: "", loading: false, request: null, receivedAt: 0, now: 0,
    status: "idle", data: null, history: [], plotError: "", showPlots: true }),
  created() {
    if (this.embedded) this.showPlots = false
    this.feed = new PlotsFeed({ publish: update => {
      const { error, ...values } = update
      Object.assign(this.$data, values, { plotError: error })
    }, unauthorized: this.unauthorized })
  },
  mounted() {
    this.activePage = true
    this.poller = new PollTimer({ read: () => this.load(), interval: 1000 })
    this.visibility = () => {
      this.now = Date.now()
      if (document.hidden) { this.request?.abort(); this.feed.stop() }
      else { this.load(); if (this.showPlots && this.mode === "local") this.feed.start() }
    }
    document.addEventListener("visibilitychange", this.visibility)
    if (this.mode === "local") { this.poller.start(); this.load(); if (this.showPlots && !document.hidden) this.feed.start() }
  },
  beforeUnmount() {
    this.activePage = false; clearTimeout(this.freshnessTimer); this.poller?.stop(); this.request?.abort(); this.request = null; this.feed.stop()
    document.removeEventListener("visibilitychange", this.visibility)
  },
  computed: {
    current() { return this.report?.current },
    live() { return !this.error && !!this.current?.fresh && this.current.ageSeconds + Math.max(0, this.now - this.receivedAt) / 1000 <= 2 },
    summary() { return trainingSummary(this.report, this.error || (this.current && !this.live ? "stale" : "")) },
    currentApplied() { return this.live && this.current?.application?.applied === true },
    rows() { return surfaceRows(this.report, this.live) },
    tracking() { return trackingView(this.report, this.live) },
    trackingValues() { return validTrackingValues(this.current?.trackingValues) ? this.current.trackingValues : null },
    trackingPlot() { return trackingPlotView(this.report, this.now - this.receivedAt) },
    pairedTrackingChart() { return chartGeometry(this.trackingPlot?.points || [], ['desiredForceAccel', 'measuredForceAccel']) },
    pairedErrorChart() { return chartGeometry(this.trackingPlot?.points || [], ['errorForceAccel']) },
    readiness() { return this.current?.details?.["Snapshot readiness"] },
    details() { return Object.entries(this.current?.details || {}) },
    losses() {
      const d = this.current?.details || {}, baseline = d["Fit baseline loss"], learned = d["Fit candidate loss"]
      return typeof baseline === "number" && Number.isFinite(baseline) && baseline >= 0 &&
        typeof learned === "number" && Number.isFinite(learned) && learned >= 0 ? { baseline, learned } : null
    },
    lateralChart() { return chartGeometry(this.history, ["desiredLateralAccel", "actualLateralAccel"]) },
    termsChart() { return chartGeometry(this.history, ["lateralP", "lateralI", "lateralD", "lateralF"]) },
  },
  methods: {
    readable, detailText, coordinateText, measurementText, sourceSeconds,
    amount(value) { return value === null || value === undefined ? "Not reported" : value.toLocaleString() },
    togglePlots() {
      this.showPlots = !this.showPlots
      if (this.showPlots && this.mode === "local" && !document.hidden) this.feed.start()
      else this.feed.stop()
    },
    async load() {
      this.now = Date.now()
      if (!this.activePage || this.mode !== "local" || this.request || document.hidden) return
      const controller = new AbortController()
      this.request = controller; this.loading = true
      try {
        const value = await requestJson("./api/flm/training-results", { request: { signal: controller.signal } })
        if (this.request !== controller || !this.activePage) return
        if (!validTrainingResults(value)) throw new Error("The device returned an unsupported training report.")
        this.report = value; this.error = ""; this.receivedAt = this.now = Date.now()
        clearTimeout(this.freshnessTimer)
        if (value.current?.fresh) this.freshnessTimer = setTimeout(() => { this.now = Date.now() },
          Math.max(0, 2000 - value.current.ageSeconds * 1000) + 1)
      } catch (error) {
        if (this.request !== controller || !this.activePage) return
        if (error.status === 401 || ["access_unavailable", "setup_required"].includes(error.code)) this.unauthorized()
        else this.error = connectionError(error, "Live update unavailable. Reconnecting automatically…")
      } finally {
        if (this.request === controller) { this.request = null; this.loading = false }
      }
    },
  },
  template: `<div class="gx-view gx-flm gx-stack">
    <header class="gx-settings__header gx-page-header"><div><h3 v-if="embedded">StarPilot Auto Learn</h3><h2 v-else>StarPilot Auto Learn</h2>
      <p>Learned steering corrections, collection progress and current application.</p></div>
      </header>
    <GxState v-if="mode !== 'local'">Auto Learn results require authenticated local Galaxy access.</GxState>
    <template v-else>
      <section class="gx-card gx-flm__panel"><div class="gx-section__header"><i class="bi bi-activity" aria-hidden="true"></i><span class="gx-section__title" role="status">{{ tracking?.label || summary }}</span></div>
        <GxState v-if="loading && !report" loading>Reading learning results…</GxState>
        <GxNotice v-if="error" tone="danger">{{ error }}</GxNotice>
        <template v-if="current"><p v-if="!tracking">{{ readable(current.reason) }}</p>
          <dl class="gx-flm__facts"><div><dt>Auto Learn</dt><dd>{{ !live ? 'Last reported: ' : '' }}{{ current.enabled === null ? 'Unknown' : current.enabled ? 'On' : 'Off' }}</dd></div>
            <div v-if="!tracking"><dt>Collection</dt><dd>{{ amount(current.progress.samples) }} samples · {{ amount(current.progress.blocks) }} retained blocks</dd></div>
            <div><dt>Current application</dt><dd>{{ currentApplied ? 'Correction reported applied' : live && current.application ? 'No correction reported applied' : 'Unknown — waiting for a current report' }}</dd></div>
          </dl><p class="gx-note">Last controller report: {{ current.ageSeconds.toFixed(1) }} seconds old when read.</p>
          <p v-if="live && current.application">{{ readable(current.application.reason) }}</p>
        </template>
        <template v-if="tracking">
          <p>{{ readable(tracking.reason) }}</p>
          <p v-if="tracking.changedParameters">Parameters under evaluation: {{ tracking.changedParameters }}</p>
          <p>Comparable episodes: {{ tracking.beforeEpisodes }} before · {{ tracking.futureEpisodes }} after<template v-if="tracking.requiredEpisodes !== null"> · {{ tracking.requiredEpisodes }} future episodes required</template>.</p>
          <p>Steering input checks: {{ readable(tracking.modelStatus) }} · Steering delay: {{ measurementText(tracking.lagMs) }}<template v-if="tracking.lagMs !== null"> ms</template></p>
          <div class="gx-flm__table-wrap"><table class="gx-flm__table" aria-label="Observed tracking before and after the correction trial">
            <thead><tr><th scope="col">Observed measurement</th><th scope="col">Before trial</th><th scope="col">Future trial episodes</th></tr></thead>
            <tbody><tr v-for="row in tracking.measurements" :key="row.label"><th scope="row">{{ row.label }}</th><td data-label="Before trial">{{ measurementText(row.before) }}</td><td data-label="Future trial episodes">{{ measurementText(row.future) }}</td></tr></tbody>
          </table></div>
          <div v-if="trackingPlot">
            <h4>Latest completed delay-paired steering episode</h4>
            <p>{{ trackingPlot.points.length }} original endpoints<template v-if="trackingPlot.sampled"> selected from {{ trackingPlot.totalEndpoints }}</template> · {{ trackingPlot.age.toFixed(1) }} seconds since the last measured pose · Alignment delay {{ (trackingPlot.lagNs / 1e6).toFixed(1) }} ms.</p>
            <p v-if="!live || !trackingPlot.bindingCurrent" class="gx-note">Earlier episode only; the current steering context is not established by this plot.</p>
            <div class="gx-plots__grid"><div><h5>Requested / measured lateral acceleration · m/s²</h5><PlotGraph :chart="pairedTrackingChart" title="Paired requested and measured lateral acceleration" /></div>
              <div><h5>Delay-paired error: requested minus measured · m/s²</h5><PlotGraph :chart="pairedErrorChart" title="Actual delay-paired steering tracking error" /></div></div>
            <p class="gx-note">Each requested value is paired by the learner with its genuine later measured pose. The horizontal axis uses those pose timestamps; selected endpoints are not resampled or upsampled. Blue is requested, orange is measured.</p>
            <details><summary>Episode source timestamps</summary><p>Latest control / applied output / measured pose: {{ sourceSeconds(trackingPlot.points.at(-1).controlMonoTime) }} / {{ sourceSeconds(trackingPlot.points.at(-1).applyMonoTime) }} / {{ sourceSeconds(trackingPlot.points.at(-1).poseMonoTime) }} monotonic seconds. Episode {{ trackingPlot.episodeId }} · Applied profile {{ trackingPlot.profileSha256 }}.</p></details>
          </div>
          <p v-else class="gx-note">No delay-paired steering episode plot has been reported yet.</p>
          <p class="gx-note">The live desired/observed plots show sampled controller telemetry; they are not actuator-delay aligned.</p>
          <p class="gx-note">Before and after use separate completed, comparable driving episodes. Unmeasured values remain “Not measured”. Passing future checks does not itself confirm controller application or saving; currently applied values require fresh controller evidence.</p>
        </template>
      </section>
      <section class="gx-card gx-flm__panel"><div class="gx-section__header"><i class="bi bi-sliders" aria-hidden="true"></i><span class="gx-section__title">Learned parameter corrections</span></div>
        <p v-if="trackingValues">Each value is an original-effect correction coordinate; baseline 0 means no automatic correction. Accepted baseline values were acknowledged by the controller. A pending trial target is an offer, not proof of application. Currently applied values require fresh controller evidence.</p>
        <p v-else>Each value is an original-effect correction coordinate. Baseline 0 means no automatic correction. Learned values are the latest evaluated proposal; current values require fresh controller application evidence.</p>
        <p class="gx-note">Speed-window coordinates multiply their original baseline by (1 + correction). Deadband and angle-assist coordinates use the controller’s physical normalizer. Raw degrees, torque and manual tune values are not published by this report.</p>
        <div class="gx-flm__table-wrap" tabindex="0" role="region" aria-label="All 30 steering correction parameters"><table class="gx-flm__table">
          <thead><tr><th scope="col">Parameter</th><th scope="col">Baseline</th><th scope="col">{{ trackingValues ? 'Accepted baseline' : 'Learned proposal' }}</th><th v-if="trackingValues" scope="col">Pending trial target</th><th scope="col">Currently applied</th><th scope="col">Support</th></tr></thead>
          <tbody><tr v-for="row in rows" :key="row.name"><th scope="row">{{ readable(row.name) }}</th><td data-label="Baseline">{{ coordinateText(row.baseline) }}</td><td :data-label="trackingValues ? 'Accepted baseline' : 'Learned proposal'">{{ coordinateText(trackingValues ? row.accepted : row.learned) }}</td><td v-if="trackingValues" data-label="Pending trial target">{{ coordinateText(row.pending) }}</td><td data-label="Currently applied">{{ coordinateText(row.applied) }}</td><td data-label="Support">{{ row.pidSupported ? 'Torque / torque-actuated PID' : 'Torque; PID unsupported (no friction stage)' }}</td></tr></tbody>
        </table></div>
        <p class="gx-note">All 30 parameters have torque stages; 26 have torque-actuated PID stages. The four friction thresholds have no PID friction measurement stage. Support describes controller capability; it does not establish a validated fit. Missing values remain “Not reported”.</p>
      </section>
      <div v-if="!tracking" class="gx-plots__grid">
        <section class="gx-card gx-flm__panel"><div class="gx-section__header"><i class="bi bi-collection" aria-hidden="true"></i><span class="gx-section__title">Collection &amp; fitting</span></div>
          <p v-if="!current">Collection begins when the device reports eligible steering examples.</p>
          <template v-if="readiness"><p>{{ readable(readiness.reason) }}</p>
            <p v-if="readiness.selectedBundles !== undefined">Selected bundles: {{ amount(readiness.selectedBundles) }} / {{ amount(readiness.requiredBundles) }} required · {{ amount(readiness.wholeBundles) }} whole bundles retained.</p>
          </template>
          <p v-if="current?.details?.['Fit in progress']">Evaluating the collected examples…</p>
          <template v-if="current?.fit"><p>{{ readable(current.fit.status) }} · {{ readable(current.fit.reason) }}</p>
            <p>Training windows: {{ amount(current.fit.trainWindows) }} · Validation windows: {{ amount(current.fit.heldoutWindows) }}</p></template>
          <p class="gx-note">Collection counts describe retained data. The fitter separately checks physical calibration, complete event support and validation.</p>
        </section>
        <section class="gx-card gx-flm__panel"><div class="gx-section__header"><i class="bi bi-bar-chart" aria-hidden="true"></i><span class="gx-section__title">Latest fit comparison</span></div>
          <dl v-if="losses"><dt>Baseline loss</dt><dd>{{ losses.baseline.toPrecision(5) }}</dd><dt>Learned proposal loss</dt><dd>{{ losses.learned.toPrecision(5) }}</dd></dl>
          <p v-else>No fit comparison has been reported yet.</p>
          <p class="gx-note">These are fitter-reported losses for the latest proposal. The live control plots are separate observations and do not measure a before/after learning benefit.</p>
        </section>
      </div>
      <section v-if="!embedded" class="gx-card gx-flm__panel"><div class="gx-section__header"><i class="bi bi-graph-up" aria-hidden="true"></i><span class="gx-section__title">Live steering response</span>
        <button type="button" class="gx-btn gx-btn--tonal" :aria-expanded="showPlots" @click="togglePlots">{{ showPlots ? 'Pause live plots' : 'Show live plots' }}</button></div>
        <template v-if="showPlots"><p role="status">{{ status === 'current' ? 'Live' : status === 'stale' ? 'Last received window — reconnecting' : 'Waiting for steering telemetry' }}</p>
          <GxNotice v-if="plotError" tone="danger">{{ plotError }}</GxNotice>
          <div class="gx-plots__grid"><section><h4>Desired / actual lateral acceleration · m/s²</h4><PlotGraph :chart="lateralChart" title="Desired and actual lateral acceleration" /></section>
            <section><h4>Steering controller terms · controller units</h4><PlotGraph :chart="termsChart" title="Steering controller P I D and F terms" terms /><p class="gx-note">P · I · D · F; absent terms leave gaps.</p></section></div>
          <p class="gx-note">Blue is desired response; orange is actual. Existing live control telemetry; missing samples leave gaps.</p>
        </template>
      </section>
      <section class="gx-card gx-flm__panel"><div class="gx-section__header"><i class="bi bi-clock-history" aria-hidden="true"></i><span class="gx-section__title">Recent findings</span></div><p v-if="!report?.history.length">No learning history has been reported yet.</p>
        <ol v-else><li v-for="(row, index) in report.history" :key="index">{{ row.time }} — {{ readable(row.status) }}: {{ readable(row.reason) }}<template v-if="row.applied"> · Correction applied at that time</template></li></ol>
        <details v-if="details.length"><summary>Report provenance and details</summary><dl><template v-for="[key, value] in details" :key="key"><dt>{{ readable(key) }}</dt><dd>{{ detailText(value) }}</dd></template></dl></details>
      </section>
    </template>
  </div>`,
}
