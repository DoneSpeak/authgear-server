package com.liteflow.auth.parser;

import com.liteflow.auth.model.BranchDefinition;
import com.liteflow.auth.model.FlowDefinition;
import com.liteflow.auth.model.StepDefinition;
import com.liteflow.auth.exception.FlowExecutionException;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML 流程定义解析器
 * 解析 login_flows.yml 文件为 FlowDefinition 模型
 */
@Component
public class FlowDefinitionParser {

    /**
     * 解析 YAML 内容
     */
    public Map<String, FlowDefinition> parse(String yamlContent) {
        try {
            LoaderOptions options = new LoaderOptions();
            Yaml yaml = new Yaml(new Constructor(YamlFlowDefinition.class, options));
            YamlFlowDefinition yamlDef = yaml.load(yamlContent);

            if (yamlDef == null || yamlDef.getLoginFlows() == null) {
                throw new FlowExecutionException("YAML 文件为空或格式不正确");
            }

            Map<String, FlowDefinition> result = new HashMap<>();
            for (YamlFlowDefinition.Flow flow : yamlDef.getLoginFlows()) {
                FlowDefinition definition = convertToFlowDefinition(flow);
                result.put(definition.getName(), definition);
            }

            return result;
        } catch (Exception e) {
            throw new FlowExecutionException("解析 YAML 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 InputStream 解析
     */
    public Map<String, FlowDefinition> parse(InputStream inputStream) {
        try {
            LoaderOptions options = new LoaderOptions();
            Yaml yaml = new Yaml(new Constructor(YamlFlowDefinition.class, options));
            YamlFlowDefinition yamlDef = yaml.load(inputStream);

            if (yamlDef == null || yamlDef.getLoginFlows() == null) {
                throw new FlowExecutionException("YAML 文件为空或格式不正确");
            }

            Map<String, FlowDefinition> result = new HashMap<>();
            for (YamlFlowDefinition.Flow flow : yamlDef.getLoginFlows()) {
                FlowDefinition definition = convertToFlowDefinition(flow);
                result.put(definition.getName(), definition);
            }

            return result;
        } catch (Exception e) {
            throw new FlowExecutionException("解析 YAML 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 YAML Flow 转换为 FlowDefinition
     */
    private FlowDefinition convertToFlowDefinition(YamlFlowDefinition.Flow flow) {
        FlowDefinition definition = new FlowDefinition();
        definition.setName(flow.getName());
        definition.setSteps(convertSteps(flow.getSteps()));
        return definition;
    }

    /**
     * 转换步骤列表
     */
    private List<StepDefinition> convertSteps(List<YamlFlowDefinition.Step> yamlSteps) {
        if (yamlSteps == null) {
            return null;
        }

        return yamlSteps.stream()
                .map(this::convertStep)
                .toList();
    }

    /**
     * 转换单个步骤
     */
    private StepDefinition convertStep(YamlFlowDefinition.Step yamlStep) {
        StepDefinition step = new StepDefinition();
        step.setType(yamlStep.getType());
        step.setOneOf(convertBranches(yamlStep.getOneOf(), yamlStep.getType()));
        step.setSteps(convertSteps(yamlStep.getSteps()));
        return step;
    }

    /**
     * 转换分支列表
     */
    private List<BranchDefinition> convertBranches(
            List<YamlFlowDefinition.Branch> yamlBranches,
            String stepType) {
        if (yamlBranches == null) {
            return null;
        }

        return yamlBranches.stream()
                .map(yamlBranch -> convertBranch(yamlBranch, stepType))
                .toList();
    }

    /**
     * 转换单个分支
     */
    private BranchDefinition convertBranch(YamlFlowDefinition.Branch yamlBranch, String stepType) {
        BranchDefinition branch = new BranchDefinition();

        // 根据步骤类型设置对应的字段
        if ("identify".equals(stepType)) {
            branch.setIdentification(yamlBranch.getIdentification());
        } else if ("authenticate".equals(stepType)) {
            branch.setAuthentication(yamlBranch.getAuthentication());
        }

        // 转换子步骤
        branch.setSteps(convertSteps(yamlBranch.getSteps()));

        return branch;
    }
}
