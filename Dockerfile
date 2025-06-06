FROM maven:3-openjdk-17 AS java
WORKDIR /build

ADD pom.xml ./
ADD lib ./lib
ADD src/main/java/com/example/xq/XqApplication.java ./src/main/java/com/example/xq/XqApplication.java

RUN mvn package  --fail-never -DskipTests
ADD src ./src

RUN mvn package  -DskipTests &&   mv target/*.jar /app.jar && rm -rf *


# 使用官方 Ubuntu 镜像
FROM ubuntu

# 更新并安装依赖
RUN  apt-get update && apt-get install -y openjdk-17-jdk \
     # 皮卡鱼依赖
    libatomic1 \
    && rm -rf /var/lib/apt/lists/*


# 设置工作目录
WORKDIR /app


# 安装pikafish
ADD lib/Pikafish-20250110 ./bin/Pikafish-20250110
RUN chmod +x ./bin/Pikafish-20250110/*

ADD lib/libopencv_java4110.so /usr/lib/
ADD template ./template
COPY --from=java /app.jar ./

# 运行示例程序
EXPOSE 80
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-Duser.timezone=Asia/Shanghai","-Dfile.encoding=UTF-8","-jar","app.jar"]
