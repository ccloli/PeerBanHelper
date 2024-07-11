package com.ghostchu.peerbanhelper.database.dao.impl;

import com.ghostchu.peerbanhelper.database.Database;
import com.ghostchu.peerbanhelper.database.dao.AbstractPBHDao;
import com.ghostchu.peerbanhelper.database.table.HistoryEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@Component
public class HistoryDao extends AbstractPBHDao<HistoryEntity, Long> {
    public HistoryDao(@Autowired Database database) throws SQLException {
        super(database.getDataSource(), HistoryEntity.class);
    }

    @Override
    public synchronized HistoryEntity createIfNotExists(HistoryEntity data) throws SQLException {
        List<HistoryEntity> list = queryForMatchingArgs(data);
        if (list.isEmpty()) {
            return super.createIfNotExists(data);
        }
        return list.getFirst();
    }

    public Map<String, Long> getBannedIps(int n) throws Exception {
        Timestamp twoWeeksAgo = new Timestamp(Instant.now().minus(14, ChronoUnit.DAYS).toEpochMilli());

        String sql = "SELECT ip, COUNT(*) AS count FROM " + getTableName() + " WHERE banAt >= ? " +
                "GROUP BY ip ORDER BY count DESC LIMIT " + n;

        Map<String, Long> result = new HashMap<>();
        var banLogs = super.queryRaw(sql, twoWeeksAgo.toString());
        try (banLogs) {
            var results = banLogs.getResults();
            results.forEach(arr -> result.put(arr[0], Long.parseLong(arr[1])));
        }
        return result;
    }

    public List<UniversalFieldNumResult> sumField(String field, double percentFilter) throws Exception {
        List<UniversalFieldNumResult> results = new ArrayList<>();
        var sql = """
                SELECT %field%,
                       SUM(%field%) AS ct,
                       SUM(%field%) / (SELECT SUM(%field%) FROM %table%) AS percent
                FROM %table%
                GROUP BY %field%
                HAVING percent > %percent%
                ORDER BY ct DESC;
                """;
        sql = sql.replace("%field%", field)
                .replace("%table%", getTableName())
                .replace("%percent%", String.valueOf(percentFilter));
        try (var resultSet = queryRaw(sql)) {
            for (String[] result : resultSet.getResults()) {
                results.add(new UniversalFieldNumResult(result[0], Long.parseLong(result[1]), Double.parseDouble(result[2])));
            }
        }
        return results;
    }

    public List<UniversalFieldNumResult> countField(String field, double percentFilter) throws Exception {
        List<UniversalFieldNumResult> results = new ArrayList<>();
        var sql = """
                SELECT %field%,
                       COUNT(%field%) AS ct,
                       COUNT(%field%) * 1.0 / (SELECT COUNT(%field%) FROM %table%) AS percent
                FROM %table%
                GROUP BY %field%
                HAVING percent > %percent%
                ORDER BY ct DESC;
                """;
        sql = sql.replace("%field%", field)
                .replace("%table%", getTableName())
                .replace("%percent%", String.valueOf(percentFilter));
        try (var resultSet = queryRaw(sql)) {
            for (String[] result : resultSet.getResults()) {
                results.add(new UniversalFieldNumResult(result[0], Long.parseLong(result[1]), Double.parseDouble(result[2])));
            }
        }
        return results;
    }

    public List<UniversalFieldDateResult> countDateField(long startAt, long endAt, Function<HistoryEntity, Timestamp> timestampGetter, Function<Calendar, Calendar> timestampTrimmer, double percentFilter) throws Exception {
        Map<DateMapping, AtomicLong> counterMap = new HashMap<>();
        try (var it = iterator()) {
            while (it.hasNext()) {
                var row = it.next();
                Timestamp field = timestampGetter.apply(row);
                long fieldT = field.getTime();
                if (!(fieldT >= startAt && fieldT <= endAt)) {
                    continue;
                }
                Calendar fuckCal = Calendar.getInstance();
                fuckCal.setTime(field);
                Calendar trimmed = timestampTrimmer.apply(fuckCal);
                DateMapping dateMapping = new DateMapping(trimmed.get(Calendar.YEAR),
                        trimmed.get(Calendar.MONTH) + 1,
                        trimmed.get(Calendar.DAY_OF_MONTH),
                        trimmed.get(Calendar.HOUR_OF_DAY), trimmed.get(Calendar.MINUTE), trimmed.get(Calendar.MINUTE));
                AtomicLong atomicLong = counterMap.getOrDefault(dateMapping, new AtomicLong(0));
                atomicLong.incrementAndGet();
                counterMap.put(dateMapping, atomicLong);
            }
        }
        // 计算总量
        long total = counterMap.values().stream().mapToLong(AtomicLong::get).sum();
        List<UniversalFieldDateResult> results = new ArrayList<>();
        for (Map.Entry<DateMapping, AtomicLong> dateMappingAtomicLongEntry : counterMap.entrySet()) {
            results.add(new UniversalFieldDateResult(dateMappingAtomicLongEntry.getKey(),
                    dateMappingAtomicLongEntry.getValue().get(),
                    (double) dateMappingAtomicLongEntry.getValue().get() / total
            ));
        }
        results.removeIf(r -> r.percent() < percentFilter);
        return results;
    }

    public record DateMapping(int year, int month, int day, int hour, int minute, int second) {

    }

    public record UniversalFieldNumResult(String data, long count, double percent) {

    }

    public record UniversalFieldDateResult(DateMapping dateMapping, long count,
                                           double percent) {

    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Data
    public static class PeerBanCount {
        private String peerIp;
        private long count;
    }
}