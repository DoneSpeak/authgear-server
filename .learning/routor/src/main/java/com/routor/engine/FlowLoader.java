package com.routor.engine;

import com.routor.model.BranchDefinition;
import com.routor.model.FlowDefinition;
import com.routor.model.StepDefinition;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

/**
 * YAML 流程加载器
 */
public class FlowLoader {

    /**
     * 加载指定名称的流程
     */
    public FlowDefinition load(String yamlContent, String flowName) {
        Map<String, FlowDefinition> flows = loadAll(yamlContent);
        return flows.get(flowName);
    }

    /**
     * 加载所有流程
     */
    public Map<String, FlowDefinition> loadAll(String yamlContent) {
        Yaml yaml = createYaml();
        Map<String, Object> root = yaml.load(yamlContent);

        if (root == null || !root.containsKey("login_flows")) {
            return Collections.emptyMap();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flowsData = (List<Map<String, Object>>) root.get("login_flows");

        Map<String, FlowDefinition> flows = new HashMap<>();
        for (Map<String, Object> flowData : flowsData) {
            FlowDefinition flow = parseFlow(flowData);
            flows.put(flow.getName(), flow);
        }

        return flows;
    }

    private Yaml createYaml() {
        LoaderOptions options = new LoaderOptions();
        return new Yaml(options);
    }

    @SuppressWarnings("unchecked")
    private FlowDefinition parseFlow(Map<String, Object> data) {
        FlowDefinition flow = new FlowDefinition();
        flow.setName((String) data.get("name"));
        flow.setType((String) data.get("type"));

        if (data.containsKey("steps")) {
            List<Map<String, Object>> stepsData = (List<Map<String, Object>>) data.get("steps");
            flow.setSteps(parseSteps(stepsData));
        }

        return flow;
    }

    private List<StepDefinition> parseSteps(List<Map<String, Object>> stepsData) {
        if (stepsData == null) return null;

        List<StepDefinition> steps = new ArrayList<>();
        for (Map<String, Object> stepData : stepsData) {
            steps.add(parseStep(stepData));
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private StepDefinition parseStep(Map<String, Object> data) {
        StepDefinition step = new StepDefinition();
        step.setType((String) data.get("type"));

        if (data.containsKey("oneOf")) {
            List<Map<String, Object>> oneOfData = (List<Map<String, Object>>) data.get("oneOf");
            step.setOneOf(parseBranches(oneOfData));
        }

        if (data.containsKey("steps")) {
            List<Map<String, Object>> subStepsData = (List<Map<String, Object>>) data.get("steps");
            step.setSteps(parseSteps(subStepsData));
        }

        return step;
    }

    @SuppressWarnings("unchecked")
    private List<BranchDefinition> parseBranches(List<Map<String, Object>> branchesData) {
        if (branchesData == null) return null;

        List<BranchDefinition> branches = new ArrayList<>();
        for (Map<String, Object> branchData : branchesData) {
            BranchDefinition branch = new BranchDefinition();
            branch.setIdentification((String) branchData.get("identification"));
            branch.setAuthentication((String) branchData.get("authentication"));

            if (branchData.containsKey("steps")) {
                List<Map<String, Object>> stepsData = (List<Map<String, Object>>) branchData.get("steps");
                branch.setSteps(parseSteps(stepsData));
            }

            branches.add(branch);
        }
        return branches;
    }
}
