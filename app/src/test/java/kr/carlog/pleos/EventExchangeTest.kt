package kr.carlog.pleos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EventExchangeTest {
    @Test fun eventOffsetPreservesSignAndDoesNotOverflow() {
        assertEquals("-60000ms", eventOffset(0, 60_000))
        assertEquals("0ms", eventOffset(60_000, 60_000))
        assertEquals("+60000ms", eventOffset(120_000, 60_000))
        assertEquals("+18446744073709551615ms", eventOffset(Long.MAX_VALUE, Long.MIN_VALUE))
    }
    private val event = EventEntity(id = "5a18ab5a-0a1e-4c91-b208-208a9e5cbf1d", vehicleId = "bcf4b06f-7654-4955-8d68-67c55fc01887", timestamp = 60_000, category = "도로 상태", memo = "따옴표 \"와 줄\n바꿈", source = "MOCK", status = "complete")
    private val samples = listOf(SampleEntity(event.id, 61_000, "fuel", 42.0, "%"), SampleEntity(event.id, 59_000, "fuel", 45.0, "%"), SampleEntity(event.id, 58_000, "latitude", 37.5, "deg"))

    @Test fun exchangeRejectsExpandedYearsForEventsAndSamples() {
        val root = JSONObject(exportEventJson(event, samples))
        for (invalid in listOf("0000-12-31T23:59:59Z", "+10000-01-01T00:00:00Z")) {
            val badEvent = JSONObject(root.toString()).put("occurredAt", invalid)
            try { parseEventJson(badEvent.toString()); fail("expanded or zero year in event") } catch (_: IllegalArgumentException) { }
            val neighboring = if (invalid.startsWith("0000")) "0001-01-01T00:00:00Z" else "9999-12-31T23:59:59Z"
            val badSample = JSONObject(root.toString()).put("occurredAt", neighboring)
            while (badSample.getJSONArray("samples").length() > 1) badSample.getJSONArray("samples").remove(1)
            badSample.getJSONArray("samples").getJSONObject(0).put("timestamp", invalid)
            try { parseEventJson(badSample.toString()); fail("expanded or zero year in sample") } catch (_: IllegalArgumentException) { }
        }
        val invalidTimestamp = java.time.Instant.parse("+10000-01-01T00:00:00Z").toEpochMilli()
        try { exportEventJson(event.copy(timestamp = invalidTimestamp), emptyList()); fail("invalid event exported") } catch (_: IllegalArgumentException) { }
        for (valid in listOf("0001-01-01T00:00:00Z", "9999-12-31T23:59:59Z")) {
            val boundary = JSONObject(root.toString()).put("occurredAt", valid).put("samples", org.json.JSONArray())
            assertEquals(java.time.Instant.parse(valid).toEpochMilli(), parseEventJson(boundary.toString()).event.timestamp)
        }
    }

    @Test fun eventMemoPreviewPreservesUnicodeAndLimitsTheVisiblePrefix() {
        assertEquals("메모 없음", eventMemoPreview(" \n"))
        assertEquals("첫 줄 둘째 줄", eventMemoPreview("첫 줄\n둘째 줄"))
        assertEquals("🚗".repeat(60) + "…", eventMemoPreview("🚗".repeat(61)))
    }

    @Test fun exportLimitFailuresIdentifyCountAndFileSizeWithoutChangingTheEvent() {
        val sample = SampleEntity(event.id, event.timestamp, "fuel", 42.0, "%")
        try {
            exportEventJson(event, List(EVENT_EXCHANGE_MAX_SAMPLES + 1) { sample })
            fail("too many samples")
        } catch (e: EventExportLimitException) {
            assertEquals(EventExportLimit.SAMPLE_COUNT, e.limit)
            assertTrue(eventExportLimitMessage(e.limit).contains("1만 개"))
        }
        val huge = event.copy(memo = "x".repeat(EVENT_EXCHANGE_MAX_BYTES))
        try {
            exportEventJson(huge, emptyList())
            fail("file too large")
        } catch (e: EventExportLimitException) {
            assertEquals(EventExportLimit.FILE_SIZE, e.limit)
            assertTrue(eventExportLimitMessage(e.limit).contains("5 MiB"))
        }
        assertEquals(EVENT_EXCHANGE_MAX_BYTES, huge.memo.length)
    }

    @Test fun exchangeCountsUnicodeCodePointsLikeTheSchema() {
        val name = "🚗".repeat(256)
        val unit = "🔋".repeat(64)
        val sample = SampleEntity(event.id, event.timestamp, name, 1.0, unit)
        val imported = parseEventJson(exportEventJson(event, listOf(sample)))
        assertEquals(sample, imported.samples.single())
        assertFalse(validSignalText(name + "🚗", unit))
        assertFalse(validSignalText(name, unit + "🔋"))
        assertFalse(validSignalText("   ", unit))
    }

    @Test fun malformedUnicodeCannotBeSilentlyReplacedDuringExport() {
        val invalidMemo = event.copy(memo = "invalid \uD800")
        try { exportEventJson(invalidMemo, emptyList()); fail("invalid memo should fail") }
        catch (_: IllegalArgumentException) { }
        assertFalse(validSignalText("fuel\uD800", "%"))
        assertFalse(validSignalText("fuel", "%\uDC00"))
    }

    @Test fun sourceLabelsDistinguishSimulationAndUnverifiedVehicleClaims() {
        assertTrue(eventSourceLabel("MOCK").contains("시뮬레이션"))
        assertTrue(eventSourceLabel("VEHICLE").contains("실차 여부 미확인"))
        assertTrue(eventSourceLabel("OTHER").contains("알 수 없는"))
        val simulated = maintenanceReport(event.copy(source = "MOCK"), emptyList(), emptyList())
        val claimedVehicle = maintenanceReport(event.copy(source = "VEHICLE"), emptyList(), emptyList())
        assertTrue(simulated.contains("시뮬레이션 (MOCK)"))
        assertTrue(claimedVehicle.contains("VEHICLE, 실차 여부 미확인"))
        assertEquals("VEHICLE", org.json.JSONObject(exportEventJson(event.copy(source = "VEHICLE"), emptyList())).getString("source"))
    }

    @Test fun v2PreservesKnownAndUnknownTimestampProvenance() {
        val received = SampleEntity(event.id, event.timestamp, "fuel", 42.0, "%", "RECEIVED")
        val measured = received.copy(name = "odometer", unit = "km", timestampKind = "MEASURED")
        val unknown = received.copy(name = "brake", timestampKind = null)
        val report = maintenanceReport(event, listOf(received), emptyList())
        assertTrue(report.contains("수신 시각"))
        val json = exportEventJson(event, listOf(received, measured, unknown))
        assertEquals(2, JSONObject(json).getInt("schemaVersion"))
        assertEquals(setOf(received, measured, unknown), parseEventJson(json).samples.toSet())
        assertEquals("시각 종류 미확인", timestampKindLabel(null))
        assertEquals("측정 시각", timestampKindLabel("MEASURED"))
    }

    @Test fun v2RequiresValidProvenanceAndV1RejectsNewFields() {
        val sample = SampleEntity(event.id, event.timestamp, "fuel", 42.0, "%", "RECEIVED")
        val root = JSONObject(exportEventJson(event, listOf(sample)))
        val item = root.getJSONArray("samples").getJSONObject(0)
        fun rejected() {
            try { parseEventJson(root.toString()); fail("invalid exchange must fail") }
            catch (_: IllegalArgumentException) { }
        }
        item.remove("timestampKind"); rejected()
        item.put("timestampKind", "ESTIMATED"); rejected()
        item.put("timestampKind", 42); rejected()
        item.put("timestampKind", "RECEIVED"); root.put("schemaVersion", 1); rejected()
        try { exportEventJson(event, listOf(sample.copy(timestampKind = "ESTIMATED"))); fail("invalid provenance") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun exportUsesVersionedContractAndUtcSampleTimes() {
        val json = JSONObject(exportEventJson(event, samples))
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("road_condition", json.getString("category"))
        assertEquals("MOCK", json.getString("source"))
        assertEquals(event.memo, json.getString("memo"))
        assertEquals("1970-01-01T00:01:00Z", json.getString("occurredAt"))
        assertEquals("1970-01-01T00:00:58Z", json.getJSONArray("samples").getJSONObject(0).getString("timestamp"))
        assertEquals(3, json.getJSONArray("samples").length())
    }

    @Test fun snapshotNeverUsesFutureSampleAndPreservesSignalTimes() {
        val snapshot = eventSnapshot(samples, event.timestamp)
        assertEquals(45.0, snapshot.getValue("fuel").value, 0.0)
        assertEquals(59_000L, snapshot.getValue("fuel").timestamp)
        assertEquals(58_000L, snapshot.getValue("latitude").timestamp)
    }

    @Test fun reportSeparatelyShowsLatestObservedPostEventValuePerSignal() {
        val data = listOf(
            SampleEntity(event.id, 62_000, "fuel", 40.0, "%", "RECEIVED"),
            SampleEntity(event.id, 61_000, "fuel", 41.0, "%", "MEASURED"),
            SampleEntity(event.id, 59_000, "fuel", 42.0, "%", "MEASURED"),
            SampleEntity(event.id, 58_000, "fuel", 43.0, "%", "RECEIVED"),
            SampleEntity(event.id, 61_500, "odometer", 1000.0, "km", "RECEIVED"),
        )
        val report = maintenanceReport(event, data, emptyList())
        assertTrue(report.contains("발생 시점 또는 그 이전의 마지막 관측값: 연료 잔량 (fuel) 42.0 %"))
        assertTrue(report.contains("연료 잔량 (fuel) 43.0 % (1970-01-01T00:00:58Z, 수신 시각)"))
        assertTrue(report.contains("발생 후 마지막 관측값: 연료 잔량 (fuel) 40.0 % (1970-01-01T00:01:02Z, 수신 시각)"))
        assertTrue(report.contains("누적 주행거리 (odometer) 1000.0 km (1970-01-01T00:01:01.500Z, 수신 시각)"))
        assertTrue(report.contains("(fuel) 41.0 % (1970-01-01T00:01:01Z, 측정 시각)"))
        assertTrue(maintenanceReport(event, emptyList(), emptyList()).contains("발생 후 마지막 관측값: 기록 없음"))
    }

    @Test fun reportExplainsCaptureStateAndDoesNotClaimAnEarlierSampleIsAtTheEvent() {
        val entry = SampleEntity(event.id, 1_000, "fuel", 42.0, "%")
        val collecting = maintenanceReport(event.copy(status = "collecting"), listOf(entry), emptyList())
        assertTrue(collecting.contains("수집 중 (미완료). 이 리포트는 미리보기를 연 시점까지의 기록입니다."))
        assertTrue(collecting.contains("발생 시점 또는 그 이전의 마지막 관측값:"))
        assertTrue(collecting.contains("1970-01-01T00:00:01Z"))
        assertTrue(maintenanceReport(event.copy(status = "interrupted"), emptyList(), emptyList()).contains("수집 중단. 후반 구간이 완성되지 않았을 수 있습니다."))
        assertTrue(maintenanceReport(event, emptyList(), emptyList()).contains("수집 종료. 모든 신호의 전후 구간이 채워졌다는 뜻은 아닙니다."))
    }

    @Test fun reportSeparatesCoverageByUnitAndTimestampKindAndLimitsSeries() {
        val entries = listOf(
            SampleEntity(event.id, 59_000, "fuel", 42.0, "%", "MEASURED"),
            SampleEntity(event.id, 60_000, "fuel", 41.0, "%", "RECEIVED"),
            SampleEntity(event.id, 61_000, "fuel", 40.0, "%", "RECEIVED"),
            SampleEntity(event.id, 61_000, "fuel", 1.0, "L", "MEASURED"),
        )
        val report = maintenanceReport(event, entries, emptyList())
        assertTrue(report.contains("연료 잔량 (fuel) · % · 측정 시각: 전·시점 1개, 이후 0개 · 1970-01-01T00:00:59Z ~ 1970-01-01T00:00:59Z"))
        assertTrue(report.contains("연료 잔량 (fuel) · % · 수신 시각: 전·시점 1개, 이후 1개 · 1970-01-01T00:01:00Z ~ 1970-01-01T00:01:01Z"))
        assertTrue(report.contains("연료 잔량 (fuel) · L · 측정 시각: 전·시점 0개, 이후 1개"))
        val many = (0..100).map { SampleEntity(event.id, 60_000, "signal$it", 1.0, "") }
        assertTrue(maintenanceReport(event, many, emptyList()).contains("나머지 1개 시계열은 리포트에서 생략됨"))
        assertTrue(maintenanceReport(event, emptyList(), emptyList()).contains("신호별 관측 범위:\n기록 없음"))
    }

    @Test fun reportShowsOnlyMatchingCurrentVehicleDetails() {
        val vehicle = VehicleEntity(id = event.vehicleId, name = "내 차", maker = "현대", model = "아이오닉", year = 2024, fuelType = "ev")
        val report = maintenanceReport(event, samples, emptyList(), vehicle)
        assertTrue(report.contains("현재 등록 차량 정보: 내 차 · 현대 · 아이오닉 · 2024년 · 전기"))
        val other = vehicle.copy(id = "another", name = "다른 차")
        val without = maintenanceReport(event, samples, emptyList(), other)
        assertTrue(without.contains("현재 등록 차량 정보: 확인할 수 없음"))
        assertFalse(without.contains("다른 차"))
    }

    @Test fun reportIncludesIdentifiersCoverageAndOnlyMatchingActiveRepairs() {
        val mapped = event.copy(originalVehicleId = "original-car")
        val repair = RecordEntity(vehicleId = event.vehicleId, type = "정비", timestamp = 70_000, odometer = 100.0,
            amount = 0.0, cost = 0, label = "점검", relatedEventId = event.id, repairOutcome = "확인")
        val other = repair.copy(id = "other", vehicleId = "other-car", label = "타 차량")
        val deleted = repair.copy(id = "deleted", deletedAt = 80_000, label = "삭제된 정비")
        val report = maintenanceReport(mapped, samples, listOf(other, deleted, repair))
        assertTrue(report.contains("이벤트 ID: ${event.id}"))
        assertTrue(report.contains("차량 ID: ${event.vehicleId}"))
        assertTrue(report.contains("원본 차량 ID: original-car"))
        assertTrue(report.contains("연료 잔량 (fuel) 45.0 % (1970-01-01T00:00:59Z, 시각 종류 미확인)"))
        assertTrue(report.contains("발생 후 마지막 관측값: 연료 잔량 (fuel) 42.0 % (1970-01-01T00:01:01Z, 시각 종류 미확인)"))
        assertTrue(report.contains("발생 전·시점 2개, 이후 1개"))
        assertTrue(report.contains("위치 신호는 단위·범위 또는 좌표 쌍"))
        assertTrue(report.contains("점검 · 확인"))
        assertFalse(report.contains("타 차량")); assertFalse(report.contains("삭제된 정비"))
        assertTrue(report.contains("진단 결과가 아닌"))
    }

    @Test fun locationUsesLatestCompletePreEventPairWithoutMixingSamples() {
        val lat = SampleEntity(event.id, 57_000, "latitude", 37.5, "deg")
        val lon = SampleEntity(event.id, 57_000, "longitude", 127.0, "deg")
        val coordinates = listOf(lat, lon,
            lat.copy(timestamp = 59_000, value = 37.6),
            lon.copy(timestamp = 58_000, value = 126.9),
            lat.copy(timestamp = 61_000), lon.copy(timestamp = 61_000),
            lon.copy(eventId = "other", timestamp = 59_000),
            lat.copy(timestamp = 58_000, value = 100.0))
        assertEquals(lat to lon, eventLocation(coordinates, event.id, event.timestamp))
        val report = maintenanceReport(event, coordinates, emptyList())
        assertTrue(report.contains("발생 전 마지막 좌표: 37.5, 127.0 (1970-01-01T00:00:57Z, 시각 종류 미확인)"))
        assertFalse(report.contains("좌표 쌍의 동일 시각을 확인할 수 없습니다"))
        assertNull(eventLocation(coordinates, "missing", event.timestamp))
    }

    @Test fun locationNeverCombinesMeasurementAndReceiptTimes() {
        val lat = SampleEntity(event.id, 59_000, "latitude", 37.5, "deg", "MEASURED")
        val lon = SampleEntity(event.id, 59_000, "longitude", 127.0, "deg", "RECEIVED")
        assertNull(eventLocation(listOf(lat, lon), event.id, event.timestamp))
        assertTrue(maintenanceReport(event, listOf(lat, lon), emptyList()).contains("시각 종류"))
        val matching = lon.copy(timestampKind = "MEASURED")
        assertEquals(lat to matching, eventLocation(listOf(lat, matching), event.id, event.timestamp))
        assertNull(eventLocation(listOf(lat.copy(timestampKind = null), matching), event.id, event.timestamp))
        assertNull(eventLocation(listOf(lat.copy(timestampKind = "BAD"), matching.copy(timestampKind = "BAD")), event.id, event.timestamp))
    }

    @Test fun reportWarnsWhenLatestCoordinatesHaveDifferentTimestamps() {
        val coordinates = listOf(
            SampleEntity(event.id, 59_000, "latitude", 37.5, "deg"),
            SampleEntity(event.id, 58_000, "longitude", 127.0, "deg"),
        )
        val report = maintenanceReport(event, coordinates, emptyList())
        assertTrue(report.contains("좌표 쌍의 동일 시각"))
        assertTrue(report.contains("1970-01-01T00:00:59Z"))
        assertTrue(report.contains("1970-01-01T00:00:58Z"))
    }

    @Test fun reportPreservesMillisecondTimestampsInUtc() {
        val precise = event.copy(timestamp = 60_123)
        val entries = listOf(SampleEntity(event.id, 60_001, "fuel", 45.0, "%"),
            SampleEntity(event.id, 60_999, "fuel", 44.0, "%"))
        val report = maintenanceReport(precise, entries, emptyList())
        assertTrue(report.contains("시각: UTC (ISO 8601)"))
        assertTrue(report.contains("1970-01-01T00:01:00.123Z"))
        assertTrue(report.contains("연료 잔량 (fuel) 45.0 % (1970-01-01T00:01:00.001Z, 시각 종류 미확인)"))
        assertTrue(report.contains("1970-01-01T00:01:00.999Z"))
        assertTrue(report.contains("발생 후 마지막 관측값: 연료 잔량 (fuel) 44.0 % (1970-01-01T00:01:00.999Z, 시각 종류 미확인)"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsSamplesFromAnotherEvent() {
        exportEventJson(event, listOf(SampleEntity("other", 0, "fuel", 0.0, "%")))
    }
}
