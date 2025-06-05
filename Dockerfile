FROM maven:3-openjdk-17 AS java
WORKDIR /build
ADD . .
RUN mvn package -q -DskipTests
RUN  mv target/*.jar /app.jar


FROM amazoncorretto:17

# 安装 OpenCV 依赖项
RUN yum update -y && \
    yum install -y \
    epel-release \
    opencv \
    opencv-devel \
    libatomic \
    && yum clean all




# 设置工作目录
WORKDIR /app

# 设置环境变量，使Java能够找到OpenCV库
ENV LD_LIBRARY_PATH=/usr/lib/jni


# 安装pikafish
ADD lib/Pikafish-20250110 ./bin/Pikafish-20250110
RUN chmod +x ./bin/Pikafish-20250110/Linux/*
RUN ./bin/Pikafish-20250110/Linux/pikafish-avx2 help


ADD template ./template
COPY --from=java /app.jar ./

# 运行示例程序
EXPOSE 80
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-Duser.timezone=Asia/Shanghai","-jar","app.jar"]
