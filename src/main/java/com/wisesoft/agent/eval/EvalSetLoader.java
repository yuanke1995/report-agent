package com.wisesoft.agent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 评测集加载（eval/goldens.yml，resources 里放一份拷贝供 classpath 读取）。
 * <p>
 * 注意：goldens.yml 的"权威版本"在项目根的 eval/ 目录（便于人工评审），
 * resources 下的是构建时拷入 classpath 的副本——两份必须保持同步，
 * 以 eval/ 为准。
 *
 * @author yuanke
 */
public class EvalSetLoader {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public EvalSet load() {
        try (InputStream in = new ClassPathResource("eval/goldens.yml").getInputStream()) {
            EvalSet set = yaml.readValue(in, EvalSet.class);
            if (set.getCases().isEmpty()) {
                throw new IllegalStateException("评测集为空");
            }
            return set;
        } catch (IOException e) {
            throw new IllegalStateException("评测集加载失败: eval/goldens.yml", e);
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class EvalSet {
        private List<Case> cases = List.of();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Case {
        private String question;
        private String expectedSql;
        private String note;
    }
}
