FROM maven:3-openjdk-17 AS java
WORKDIR /build
ADD pom.xml ./
RUN mvn dependency:go-offline --fail-never
ADD . .
RUN mvn package -DskipTests -q  mv target/*.jar /app.jar && rm -rf *


FROM amazoncorretto:17

# 安装OpenCV依赖
RUN apt-get update && \
    apt-get install -y \
    libopencv-dev \
    opencv-data \
    && rm -rf /var/lib/apt/lists/*

# 设置工作目录
WORKDIR /app

# 设置环境变量，使Java能够找到OpenCV库
ENV LD_LIBRARY_PATH=/usr/lib/jni


# 安装pikafish


ADD template ./template

COPY --from=java /app.jar ./

# 运行示例程序
EXPOSE 80
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-Duser.timezone=Asia/Shanghai","-jar","app.jar"]
