package com.routor.demo;

import com.routor.engine.FlowEngine;
import com.routor.engine.FlowLoader;
import com.routor.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * 命令行演示程序
 */
public class FlowDemo {
    public static void main(String[] args) throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/flows.yaml"));
        FlowLoader loader = new FlowLoader();
        FlowEngine engine = new FlowEngine(loader.loadAll(yaml));

        Scanner scanner = new Scanner(System.in);

        System.out.println("=== Routor Flow Executor Demo ===\n");
        System.out.println("可用流程:");
        System.out.println("  - default_login_flow\n");

        System.out.print("请输入流程名称 (默认: default_login_flow): ");
        String flowName = scanner.nextLine().trim();
        if (flowName.isEmpty()) {
            flowName = "default_login_flow";
        }

        FlowInstance flow = engine.create(flowName);
        System.out.println("\n流程已创建: " + flow.getFlowId());
        System.out.println("当前路径: []\n");

        ExecutionResult result;
        do {
            result = engine.execute(flow, null);

            if (result.isNeedInput()) {
                System.out.println("【" + result.getPath().size() + "】请选择:");
                for (Option opt : result.getOptions()) {
                    String hint = opt.isHasSubSteps() ? " (有子步骤)" : "";
                    System.out.println("  " + opt.getId() + hint);
                }

                System.out.print("\n输入选择: ");
                String input = scanner.nextLine().trim();
                result = engine.execute(flow, input);
            }

            System.out.println("当前路径: " + result.getPath());

            if (result.isError()) {
                System.out.println("错误: " + result.getMessage());
                System.out.println("请重新选择\n");
            }

        } while (!result.isCompleted() && !result.isError());

        if (result.isCompleted()) {
            System.out.println("\n✓ 流程完成!");
            System.out.println("最终路径: " + result.getPath());
        }

        scanner.close();
    }
}
