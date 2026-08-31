package com.wisesoft.agent.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 语义层加载器：把 semantic-model/ 下的 YAML 读成 {@link SemanticModel}。
 * <p>
 * 解析用 FAIL_ON_UNKNOWN_PROPERTIES=true（Jackson 默认）。语义层是人工维护的
 * 配置，写错一个字段名如果被静默忽略，表现出来会是"某个口径莫名其妙不生效"，
 * 排查成本极高。宁可启动失败。
 *
 * @author yuanke
 */
@Slf4j
public class SemanticModelLoader {

    private static final String TABLES_PATTERN = "classpath*:semantic-model/tables/*.yml";
    private static final String TEMPLATES_PATTERN = "classpath*:semantic-model/templates/*.yml";
    private static final String METRICS_PATH = "classpath:semantic-model/metrics.yml";
    private static final String JOINS_PATH = "classpath:semantic-model/joins.yml";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // role 写成 id / ID / Id 都接受：YAML 是人手写的，大小写不该成为门槛
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public SemanticModel load() {
        List<TableDef> tables = loadEach(TABLES_PATTERN, TableDef.class);
        List<ReportTemplate> templates = loadEach(TEMPLATES_PATTERN, ReportTemplate.class);
        MetricsFile metricsFile = loadOne(METRICS_PATH, MetricsFile.class);
        JoinsFile joinsFile = loadOne(JOINS_PATH, JoinsFile.class);

        SemanticModel model = new SemanticModel(
                tables,
                metricsFile.getMetrics(),
                joinsFile.getJoins(),
                joinsFile.getForbiddenJoins(),
                templates);

        log.info("语义层加载完成：{} 张表 / {} 个指标 / {} 条 join 路径 / {} 个报表模板",
                model.getTables().size(), model.getMetrics().size(),
                model.getJoins().size(), model.getTemplates().size());
        return model;
    }

    private <T> List<T> loadEach(String pattern, Class<T> type) {
        List<T> list = new ArrayList<>();
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            throw new IllegalStateException("语义层扫描失败: " + pattern, e);
        }
        for (Resource r : resources) {
            try (InputStream in = r.getInputStream()) {
                list.add(yaml.readValue(in, type));
            } catch (JsonMappingException e) {
                throw new IllegalStateException(
                        "语义层解析失败: " + r.getFilename() + " —— " + e.getOriginalMessage(), e);
            } catch (IOException e) {
                throw new IllegalStateException("语义层读取失败: " + r.getFilename(), e);
            }
        }
        if (list.isEmpty()) {
            throw new IllegalStateException("语义层为空，未匹配到任何文件: " + pattern);
        }
        return list;
    }

    private <T> T loadOne(String path, Class<T> type) {
        Resource r = resolver.getResource(path);
        if (!r.exists()) {
            throw new IllegalStateException("语义层文件缺失: " + path);
        }
        try (InputStream in = r.getInputStream()) {
            return yaml.readValue(in, type);
        } catch (JsonMappingException e) {
            throw new IllegalStateException(
                    "语义层解析失败: " + path + " —— " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("语义层读取失败: " + path, e);
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    static class MetricsFile {
        private List<MetricDef> metrics = List.of();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    static class JoinsFile {
        private List<JoinDef> joins = List.of();
        private List<ForbiddenJoin> forbiddenJoins = List.of();
    }
}
