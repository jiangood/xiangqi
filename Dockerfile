FROM maven:3-openjdk-17 AS java
WORKDIR /build
ADD . .
RUN mvn package -q -DskipTests
RUN  mv target/*.jar /app.jar


# 使用官方 Ubuntu 镜像
FROM ubuntu:22.04

# 设置语言、时区
ENV LANG zh_CN.UTF-8
ENV LC_ALL zh_CN.UTF-8
ENV TZ=Asia/Shanghai

# 避免交互式安装提示
ENV DEBIAN_FRONTEND=noninteractive

# 更新并安装依赖
RUN apt-get update && apt-get install -y \
    wget \
    software-properties-common \
    && rm -rf /var/lib/apt/lists/*

# 安装 Java 17
RUN apt-get update && apt-get install -y openjdk-17-jdk \
    && java -version

# 通过 apt 安装 OpenCV（Python + C++ 支持）
RUN apt-get update && apt-get install -y \
    libopencv-dev \
    && rm -rf /var/lib/apt/lists/*




# 设置工作目录
WORKDIR /app

# 设置环境变量，使Java能够找到OpenCV库
ENV LD_LIBRARY_PATH=/usr/lib/jni


# 安装pikafish

ADD lib/Pikafish-20250110 ./bin/Pikafish-20250110
RUN chmod +x ./bin/Pikafish-20250110/Linux/*


ADD template ./template
COPY --from=java /app.jar ./

# 运行示例程序
EXPOSE 80
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-Duser.timezone=Asia/Shanghai","-jar","app.jar"]
