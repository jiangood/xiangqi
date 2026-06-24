# 中国象棋局面分析接口
```shell
java -jar app.jar <image-file-path>
```

# FEN 格式
https://www.xqbase.com/protocol/cchess_move.htm
![img_2.png](docs/images/img_2.png)

![img_1.png](docs/images/img_1.png)

红色大写，黑色小写

# 引擎
https://www.pikafish.com

根据 引擎介绍.txt 

大多数情况下，引擎速度：vnni512>avx512>avx512f>avxvnni>bmi2>avx2>sse41-popcnt>ssse3 棋友根据自己的CPU选择相应的引擎


## 命令行
https://blog.csdn.net/gitblog_01232/article/details/143039330
https://github.com/official-pikafish/Pikafish/wiki/UCI-&-Commands
uci

ucinewgame

position fen r2ak1b1r/4a4/2n1b1nc1/p1p1p1p1p/2c6/6P2/P3P3P/N1CC2N2/9/1RBAKAB1R w

go depth 20

## 安装 

linux

yum install libatomic



# Ubuntu 下编译 opencv 静态库
ai 提示词：Ubuntu 下使用java调用opencv，如何通过源码编译得到so文件

```shell
sudo apt update
sudo apt install -y build-essential cmake git libgtk2.0-dev pkg-config \
    libavcodec-dev libavformat-dev libswscale-dev \
    python3-dev python3-numpy \
    ant openjdk-8-jdk  # Java 环境（推荐 JDK 8 或 11）
    
wget https://github.com/opencv/opencv/archive/refs/tags/4.11.0.zip -O opencv.zip
apt-get install unzip
unzip opencv.zip
cd opencv-4.11.0/
mkdir build && cd build
cmake -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=ON \
      -DBUILD_opencv_java=ON \
      -DOPENCV_ENABLE_NONFREE=ON \  # 如需专利算法（如 SIFT）
      -DWITH_GTK=ON \
      ..



make -j$(nproc)  # 使用所有 CPU 核心加速编译
sudo make install
```
Java 库（.so）: build/lib/libopencv_java<版本号>.so
例如：libopencv_java455.so

JAR 包: build/bin/opencv-<版本号>.jar
