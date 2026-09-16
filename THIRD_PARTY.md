# 第三方组件

- Spring Boot / Spring Framework / Spring Security / Spring AI：Apache License 2.0。
- Apache Maven Wrapper：Apache License 2.0，脚本中保留原始许可证声明。
- Apache ECharts5.6.0：Apache License 2.0，前端完整分发文件位于static/vendor，许可证见同目录ECHARTS-LICENSE.txt，保留发行文件中的版权声明。
- H2、MySQL Connector/J、Redis客户端等遵循各自许可证。可执行JAR保留其原始嵌套依赖JAR及META-INF许可信息。

没有附带JDK、MySQL/Redis服务端、模型权重或浏览器。Docker文件只引用镜像。依赖精确版本以pom.xml和Maven依赖树为准。

实现参考：
- Spring AI结构化输出：https://docs.spring.io/spring-ai/reference/1.0/api/structured-output-converter.html
- Spring AI工具调用：https://docs.spring.io/spring-ai/reference/1.0/api/tools.html
- Apache ECharts数据集：https://echarts.apache.org/handbook/zh/concepts/dataset/

项目固定版本用于复现，不表示这些版本始终为最新版本。
