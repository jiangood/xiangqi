FROM maven:3-openjdk-17 AS java
WORKDIR /build
ADD . .
RUN mvn package -q -DskipTests
RUN  mv target/*.jar /app.jar


# 使用官方Ubuntu镜像作为基础
FROM ubuntu:22.04

# 设置环境变量以避免交互式安装提示
ENV DEBIAN_FRONTEND=noninteractive

# 更新软件包列表并安装必要工具
RUN apt-get update && apt-get install -y \
    wget \
    unzip \
    build-essential \
    cmake \
    git \
    pkg-config \
    libgtk-3-dev \
    libavcodec-dev \
    libavformat-dev \
    libswscale-dev \
    libv4l-dev \
    libxvidcore-dev \
    libx264-dev \
    libjpeg-dev \
    libpng-dev \
    libtiff-dev \
    gfortran \
    openexr \
    libatlas-base-dev \
    python3-dev \
    python3-numpy \
    libtbb2 \
    libtbb-dev \
    libdc1394-22-dev \
    software-properties-common

# 安装Java 17
RUN apt-get install -y openjdk-17-jdk && \
    java -version

# 安装OpenCV
RUN cd /opt && \
    wget -O opencv.zip https://github.com/opencv/opencv/archive/4.8.0.zip && \
    wget -O opencv_contrib.zip https://github.com/opencv/opencv_contrib/archive/4.8.0.zip && \
    unzip opencv.zip && \
    unzip opencv_contrib.zip && \
    mv opencv-4.8.0 opencv && \
    mv opencv_contrib-4.8.0 opencv_contrib && \
    cd opencv && \
    mkdir build && \
    cd build && \
    cmake -D CMAKE_BUILD_TYPE=RELEASE \
          -D CMAKE_INSTALL_PREFIX=/usr/local \
          -D INSTALL_C_EXAMPLES=ON \
          -D INSTALL_PYTHON_EXAMPLES=ON \
          -D OPENCV_GENERATE_PKGCONFIG=ON \
          -D OPENCV_EXTRA_MODULES_PATH=/opt/opencv_contrib/modules \
          -D BUILD_EXAMPLES=ON .. && \
    make -j$(nproc) && \
    make install && \
    ldconfig && \
    rm /opt/opencv.zip /opt/opencv_contrib.zip

# 清理APT缓存以减少镜像大小
RUN apt-get clean && \
    rm -rf /var/lib/apt/lists/*



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
