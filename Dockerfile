FROM maven:3-openjdk-17 AS java

ADD pom.xml ./
RUN mvn dependency:go-offline -q -B -T 1C --fail-never
ADD src ./src
ADD lib ./lib
RUN mvn package -q -DskipTests
RUN  mv target/*.jar /app.jar


# 使用官方 Ubuntu 镜像
FROM ubuntu

# 设置语言、时区
ENV LANG zh_CN.UTF-8
ENV LC_ALL zh_CN.UTF-8
ENV TZ=Asia/Shanghai

# 避免交互式安装提示
ENV DEBIAN_FRONTEND=noninteractive


# 更新并安装依赖
RUN  apt-get update && apt-get install -y openjdk-17-jdk \
     # 皮卡鱼依赖
    libatomic1 \
    && rm -rf /var/lib/apt/lists/*


# 安装指定版本opencv

RUN sudo apt install -y cmake g++
#安装项目构建工具，有两个选择，make或ninja， ninja自动支持多线程，make得自己加-j选项，这里先全安装上
RUN sudo apt install make ninja-build
RUN wget https://github.com/opencv/opencv/archive/refs/tags/4.11.0.zip && unzip  4.11.0.zip && ls && cd  4.11.0 && \
    cmake -B build -GNinja -DCMAKE_INSTALL_PREFIX=~/lib/opencv4.9.0_install && \
    cmake --build build/ &&\
    cmake --install build/




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
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-Duser.timezone=Asia/Shanghai","-Dfile.encoding=UTF-8","-jar","app.jar"]
