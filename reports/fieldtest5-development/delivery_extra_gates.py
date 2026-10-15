"""Pure host-only .5 release gates. No subprocess, device, network or file writes.

Called only after the existing final/prepared validators. Recomputes OMR and audio;
registers every required original byte in the caller's archive registry.
"""
from pathlib import Path
import json
import re
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def need(ok, message):
    if not ok: raise ValueError(message)


def same_json(left, right):
    return json.dumps(left, sort_keys=True, allow_nan=False) == json.dumps(right, sort_keys=True, allow_nan=False)


def identity_inputs(archive, final, prepared):
    boundary = prepared['deviceCompletedAtMillis']
    need(type(boundary) is int and boundary > 0, 'Actual positive prepared completion boundary required')
    need(prepared['sha'] == final['sha'] and prepared['device'] == final['device'] and prepared['artifacts'] == final['artifacts'], 'Extra gate final/prepared identity differs')
    need(isinstance(prepared.get('version'), str) and re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', prepared['version']) and
         prepared['version'] == final.get('version'), 'Extra gate final/prepared version differs or is missing')
    need(re.fullmatch('[a-f0-9]{40}', final['sha']), 'Invalid prepared commit')
    return prepared['artifacts']['app']['sha256'], prepared['artifacts']['tests']['sha256'], final['sha'], boundary


def validate_omr(archive, registry, final, prepared, path):
    app, tests, commit, boundary = identity_inputs(archive, final, prepared)
    path = archive.register(registry, archive.local(str(path)))
    saved = archive.read(path)
    need(saved.get('status') == 'PASSED' and saved.get('releaseEligibility') == 'POST_PREPARE_EVIDENCE_VERIFIED', 'OMR report failed or is DEVELOPMENT_ONLY')
    identity = saved['identity']
    need(identity.get('versionName') == prepared['version'], 'OMR report version differs from exact prepare version')
    need(identity.get('appSha256') == app and identity.get('testApkSha256') == tests and identity.get('commit') == commit and identity.get('notBeforeMs') == boundary, 'OMR report differs from exact prepare APK/commit/boundary')
    need(identity.get('serial') == final['device'], 'OMR report device differs from final')
    need(archive.stamp(identity['finishedAt']) <= archive.stamp(final['finishedAt']), 'OMR completed after final; rerun finalization')
    source = archive.register(registry, HERE/'validate_omr_delivery.py')
    validator = archive.load(source, 'omr_release_extra_gate')
    actual = validator.validate_inputs(saved['batchReport']['path'], app, tests, commit, boundary,
        saved['comparisons']['S02']['comparison']['path'], saved['comparisons']['S03']['comparison']['path'], expected_version=prepared['version'])
    need(same_json(actual, {k:v for k,v in saved.items() if k != 'generatedAt'}), 'OMR report changed or differs from full pure recomputation')
    archive.register_seal(registry, actual['evidenceSeal'])
    return dict(report=dict(path=str(path), **archive.record(path)), validation=actual)


def require_audio_identity(archive, data, final, prepared):
    app, tests, commit, boundary = identity_inputs(archive, final, prepared)
    need(data.get('schemaVersion') == 1 and type(data.get('schemaVersion')) is int and data.get('status') == 'HOST_COMPARISON_COMPLETE', 'Audio comparison is incomplete or failed')
    need(data.get('appSha256') == app and data.get('testApkSha256') == tests and data.get('commit') == commit and data.get('device') == final['device'], 'Audio comparison APK/commit/device differs')
    prepare_path = archive.local(final['preparedReport']['path'])
    need(archive.local(data['prepareReport']) == prepare_path and data.get('prepareReportSha256') == final['preparedReport']['sha256'] == archive.sha(prepare_path), 'Audio comparison uses a different prepared report')
    need(archive.stamp(prepared['finishedAt']) <= archive.stamp(data['createdAt']) <= archive.stamp(final['finishedAt']), 'Audio comparison is outside prepared/final window')
    for key in ('allFifteenRaw88IdenticalToFinalFieldtest4', 'allStrictRunEvidencePassed'):
        need(data.get(key) is True, 'Audio required conclusion missing: ' + key)
    for key in ('regressionFailures', 'previouslyTrueSubgateRegressions'):
        need(data.get(key) == [], 'Audio regression is not empty: ' + key)
    for key in ('modelWeightsChanged', 'thresholdsOrReferenceChanged', 'physicalCurrentApkAccuracyPassed'):
        need(data.get(key) is False, 'Audio changed scope/claims: ' + key)
    for key in ('physicalPhoneAcceptance', 'physicalPedalAcceptance', 'heldOutAcceptance'):
        need(data.get(key) == 'UNVERIFIED', 'Audio unsupported real-world claim: ' + key)
    need(data.get('oldPhoneD4D5Failures') == 'NOT_REEVALUATED_HERE_REMAIN_KNOWN_FAILURES', 'Historical music failures hidden')
    return app, tests, commit, boundary


def require_case_nonregression(value):
    need(value.get('status') == 'FINAL_FIELDTEST4_REGRESSION_PASSED' and value.get('regressionFailures') == [] and value.get('originalFieldtest3RegressionFailures') == [], 'Actual .4/.3 audio subgate or whole-case regression')
    raw = value['raw88VsFinalFieldtest4']
    need(all(raw.get(k) is True for k in ('exactJsonNumericArraysEqual', 'audioTimesExactlyEqual', 'sequenceSha256Equal')), 'Actual raw88 arrays/times/sequence changed')


def validate_audio(archive, gate, registry, final, prepared, path):
    path = archive.register(registry, archive.local(str(path)))
    data = archive.read(path)
    app, tests, commit, boundary = require_audio_identity(archive, data, final, prepared)
    sealed = archive.register_seal(registry, data['evidenceSeal'])
    def required(path, expected=None):
        path = archive.local(str(path))
        need(path in sealed, 'Audio report omitted required original evidence: ' + str(path))
        return archive.register(registry, path, expected)
    def read(path): return archive.read(required(path))
    compare_path = required(HERE/'compare_prepared_15.py')
    need(archive.local(data['comparisonTool']) == compare_path and data['comparisonToolSha256'] == archive.sha(compare_path), 'Audio comparison tool differs')
    cmp = archive.load(compare_path, 'audio_extra_pure_functions')
    baseline_path = required(HERE/'audio_fieldtest4_baseline.py')
    prior = archive.load(baseline_path, 'audio_extra_final4_baseline')
    replay_path = archive.register(registry, HERE/'replay_full_trace.py')
    replay_module = archive.load(replay_path, 'audio_extra_replay_pure_functions')
    archive.register(registry, ROOT/'tools/check-fieldtest.py')
    for filename, digest in prior.FROZEN.items():
        item = required(prior.DIRECTORY/filename)
        need(archive.sha(item) == digest, 'Frozen final .4 comparison changed')
    final4_records = prior.inspect_final4()
    report_folder = path.parent
    manifest_path = required(report_folder/'final-regression-manifest.json')
    fixed_path = archive.register(registry, report_folder/'fixed-website-validation.json')
    # Both manifest and its exact actual runs must be the ones independently finalized.
    final_sealed = {archive.local(row['path']):row for row in final['finalEvidenceSeal']}
    need(manifest_path in final_sealed, 'Audio run manifest was not sealed by finalization')
    archive.verify_record(manifest_path, final_sealed[manifest_path])
    fixed = gate.validate_website_regression(manifest_path, app, tests, final['device'], not_before_ms=boundary)
    need(fixed == archive.read(fixed_path), 'Audio website verdict differs from actual pure revalidation')
    need(fixed['evidenceAcceptance'] == 'PASSED' and fixed['regressionFailures'] == [], 'Actual original music subgate regression')
    need(fixed['manifestSha256'] == archive.sha(manifest_path) and final['websiteRegressionCounts'] == {k:fixed[k] for k in ('total','passed','failed','coreTotal','corePassed')} and final['websiteRegressionMusicAcceptance'] == fixed['musicAcceptance'], 'Audio comparison differs from final website counts/manifest')
    manifest = archive.read(manifest_path)
    need(len(manifest['runs']) == 15 and len(set(manifest['runs'])) == 15, 'Audio comparison lacks exactly 15 unique runs')
    for candidate in final_sealed:
        if candidate.name == 'website-regression-validation.json':
            actual_final_website = archive.read(candidate)
            need(actual_final_website == fixed, 'Audio uses different runs from final website gate')
            archive.register(registry, candidate, final_sealed[candidate])
            break
    else: raise ValueError('Final website result missing')
    for source, record in gate.revalidate_website_evidence(fixed, manifest_path).items():
        required(source, record)
    final4_first = next(iter(final4_records.values()))['run']
    old4 = gate.validate_website_regression(cmp.FINAL4, prior.APP, prior.TEST, final4_first['serial'])
    need(old4 == read(prior.DIRECTORY/'fixed-website-validation.json'), 'Frozen final .4 verdict differs')
    need(old4 == archive.read(archive.register(registry, report_folder/'final-fieldtest4-revalidated.json')), 'Audio .4 revalidation output differs')
    for source, record in gate.revalidate_website_evidence(old4, cmp.FINAL4).items(): required(source, record)
    base = read(gate.WEBSITE_BASELINE)
    cases = {x['case']:x for x in base['cases']}; older = {x['case']:x for x in old4['cases']}
    need(set(cases) == set(older) == {x['case'] for x in fixed['cases']}, 'Audio baseline case inventory differs')
    log_path = required(archive.local(data['replayLog']))
    need(archive.sha(log_path) == data['replayLogSha256'], 'Actual audio JSONL log changed')
    logs = [json.loads(x) for x in log_path.read_text(encoding='utf-8-sig').splitlines() if x.strip()]
    need(len(logs) == 15 and all(x.get('status') == 'TRACE_COMPLETE_MUSIC_NOT_ADJUDICATED' and x.get('error') is None for x in logs), 'Exactly 15 successful actual replay rows required')
    paths = [archive.local(str(Path(x['output'])/'run-manifest.json')) for x in logs]
    need(len(set(paths)) == 15 and paths == [archive.local(x) for x in manifest['runs']], 'Audio log uses another run/order')
    expected4 = dict(commit=prior.COMMIT, appSha256=prior.APP, testApkSha256=prior.TEST, manifest=str(cmp.FINAL4.relative_to(ROOT)), frozenFiles=prior.FROZEN)
    need(data['finalFieldtest4'] == expected4, 'Audio baseline identity differs')
    old_full = {'s01-off':'20260924t000938z-baseline-s01-off-9591bff0', 's02-off':'20260924t000947z-baseline-s02-off-091129f3', 'half':'20260924t001007z-baseline-half-4a5aa11e'}
    results = []; changes = []; independent = []
    cmp.seen.clear()
    for row in fixed['cases']:
        name = row['case']; case = cases[name]; previous = older[name]
        folder = archive.local(row['runManifest']).parent
        need(folder.is_relative_to(cmp.RUN_ROOT), 'Audio run outside .5 run root')
        rm = read(folder/'run-manifest.json')
        need(rm['runId'] == folder.name and rm['label'] == data['runLabel'] and rm['expectedAppSha256'] == app and rm['expectedTestSha256'] == tests and rm['serial'] == final['device'], 'Audio run label/identity differs')
        need(rm['runnerSha256'] == archive.sha(replay_path) and rm['runnerDependencies']['audioBaselineHelperSha256'] == archive.sha(baseline_path), 'Actual audio runner/helper changed')
        meta = read(folder/'metadata.json'); replay = read(folder/'practice-replay-result.json')
        need(type(meta.get('startedAtMillis')) is int and meta['startedAtMillis'] >= boundary, 'Audio run predates exact prepared completion')
        need(meta.get('testCompileGitCommit') == commit and meta.get('testCompileGitCommitIsInstalledAppIdentity') is False, 'Audio test compile commit mismatch or identity overclaim')
        need(archive.stamp(rm['finishedAtUtc']) <= archive.stamp(data['createdAt']), 'Audio comparison predates actual run completion')
        extra = cmp.validate_run_extras(folder, rm, prior)
        need(extra['status'] == 'PASSED', 'Actual audio command/XPR archival failure')
        check = prior.compare_case(folder, case, final4_records, replay_module)
        require_case_nonregression(check)
        need(check == rm['finalFieldtest4Comparison'] == archive.read(archive.register(registry, folder/'final-fieldtest4-comparison.json')), 'Per-run .4 comparison differs on actual recomputation')
        need(check['verdict'] == row['verdict'], 'Independent .4 comparison and final scorer disagree')
        independent.append(check)
        oldfolder = archive.local(previous['runManifest']).parent
        raw = cmp.compare_raw(folder, oldfolder); raw['rawSequenceSha256Equal'] = row['trace']['rawSequenceSha256'] == previous['trace']['rawSequenceSha256']
        previous_files = {Path(x['path']).name:read(ROOT/x['path']) for x in case['originalArtifacts']}
        changes3 = {g:cmp.changes(previous_files[f]['gates'], row['verdict'][g+'Gates']) for g,f in [('music','acceptance-metrics.json'),('acoustic','original-acoustic-evaluation.json')]}
        changes4 = {g:cmp.changes(previous['verdict'][g+'Gates'],row['verdict'][g+'Gates']) for g in ('music','acoustic')}
        for version, grouped in [('fieldtest.3',changes3),('finalFieldtest4',changes4)]:
            for group, items in grouped.items(): changes += [dict(case=name,baseline=version,group=group,**change) for change in items]
        oldraw = dict(status='UNVERIFIED_NO_FULL_RAW88_FOR_THIS_ORIGINAL_CASE',scope='Original sparse accepted MIDI events are not complete model raw88 arrays')
        if name in old_full:
            olddir = ROOT/'reports/fieldtest4-development/runs'/old_full[name]; oldrun = read(olddir/'run-manifest.json')
            need(oldrun['status']=='TRACE_COMPLETE_MUSIC_NOT_ADJUDICATED' and oldrun['label']=='baseline' and oldrun['case']==name, 'Wrong .3 full raw baseline')
            need(oldrun['expectedAppSha256']==base['baselineApp']['sha256'] and oldrun['modelSha256']==gate.MODEL_SHA and oldrun['input']==case['input'] and oldrun['timeline']==case['timeline'], 'Old raw input/model identity differs')
            need(oldrun['installedIdentityBefore']==oldrun['installedIdentityAfter']==dict(appSha256=oldrun['expectedAppSha256'],testSha256=oldrun['expectedTestSha256']), 'Old raw installed identity differs')
            oldraw=dict(status='FULL_RAW88_COMPARED',validation=cmp.raw_digest(olddir,case,oldrun['expectedAppSha256'],oldrun['expectedTestSha256']),comparison=cmp.compare_raw(folder,olddir))
        before=meta['engineBeforeStop']
        failures={g:[k for k,v in row['verdict'][g+'Gates'].items() if not v and k not in row['verdict']['inapplicable']] for g in ('music','acoustic')}
        results.append(dict(case=name,runManifest=row['runManifest'],music=row['verdict']['musicAcceptance'],originalMusic=case['originalStatus'],finalFieldtest4Music=previous['verdict']['musicAcceptance'],onsetOriginal=case['originalOnset'],onsetFixed=row['onset'],onsetFinalFieldtest4=previous['onset'],freeOriginal=case['originalFree'],freeFixed=row['free'],freeFinalFieldtest4=previous['free'],stopOriginal=case['originalStop'],stopFixed=row['stop'],stopFinalFieldtest4=previous['stop'],unconfirmedCursorDifference=dict(free=replay['sessions']['free']['finalCursor']-replay['sessions']['free']['correct'],stop=replay['sessions']['stop']['finalCursor']-replay['sessions']['stop']['correct'],meaning='Final cursor minus explicitly CORRECT count; cursor completion is not all targets correctly confirmed.'),strictRunEvidence=extra,raw88VsFinalFieldtest4=raw,raw88VsFieldtest3=oldraw,subgateChangesVsFieldtest3=changes3,subgateChangesVsFinalFieldtest4=changes4,applicableFailedGates=failures,inapplicable=row['verdict']['inapplicable'],queue=dict(overflowCount=before['overflowCount'],droppedFrames=before['droppedFrames'],droppedSamples=before['droppedSamples'],rejectedBeforeReadyFrames=before['rejectedBeforeReadyFrames'],queueOccupancy=before['queueOccupancy'],processedSamples=before['processedSamples'],readyBeforePcm=meta['readyObservedBeforeFirstPcm']),frames=row['trace']['frames']))
    regressions=[x for x in changes if x['previous'] is True and x['current'] is False]
    failures=cmp.comparison_failures(results,regressions,fixed['evidenceAcceptance'])
    need(not failures, 'Recomputed audio whole-case/subgate/raw/XPR regression')
    actual=dict(cases=results,regressionFailures=failures,previouslyTrueSubgateRegressions=regressions,subgateImprovements=[x for x in changes if x['previous'] is False and x['current'] is True],allSubgateChanges=changes,totalFrames=sum(x['frames'] for x in results),totalSamples=sum(x['queue']['processedSamples'] for x in results),queueOverflow=sum(x['queue']['overflowCount'] for x in results),droppedFrames=sum(x['queue']['droppedFrames'] for x in results),droppedSamples=sum(x['queue']['droppedSamples'] for x in results),fieldtest3Raw88Coverage=dict(fullTraceCasesCompared=list(old_full),notAvailableCases=[x['case'] for x in results if x['case'] not in old_full],noClaimOfAllFifteenRawEquality=True),musicalAcceptance=fixed['musicAcceptance'],fixedTraceEvidenceAcceptance=fixed['evidenceAcceptance'],**{k:fixed[k] for k in ('total','passed','failed','coreTotal','corePassed')})
    for key,value in actual.items(): need(same_json(data.get(key),value), 'Audio report differs from pure recomputation: '+key)
    for item,record in cmp.seen.items(): required(item,record)
    # Capture helper dependencies plus every required sealed original byte for archive/push.
    archive.register(registry, report_folder/'SUMMARY.md')
    return dict(report=dict(path=str(path),**archive.record(path)),status='PASSED',identity=dict(commit=commit,appSha256=app,testApkSha256=tests,device=final['device'],notBeforeMs=boundary,prepareReportSha256=data['prepareReportSha256'],runLabel=data['runLabel']),counts={k:fixed[k] for k in ('total','passed','failed','coreTotal','corePassed')},musicalAcceptance=fixed['musicAcceptance'],allFifteenRaw88IdenticalToFinalFieldtest4=True,regressionFailures=[],independentlyRecomputedRuns=independent,teacherConfirmed=False,physicalPhoneAcceptance='UNVERIFIED')


def validate_extra_gates(archive, gate, registry, final, prepared, omr_validation_path, audio_comparison_path):
    """Pure except for adding verified records to the caller-owned in-memory registry."""
    extra = {}
    archive.register(extra, Path(__file__).resolve())
    omr = validate_omr(archive, extra, final, prepared, omr_validation_path)
    audio = validate_audio(archive, gate, extra, final, prepared, audio_comparison_path)
    for path,expected in extra.items(): archive.register(registry,path,expected)
    seal=[dict(path=str(path),**expected) for path,expected in sorted(extra.items())]
    return dict(status='PASSED',omr=omr,audio=audio,evidenceSeal=seal)
