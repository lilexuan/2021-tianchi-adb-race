package com.aliyun.adb.contest;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.Future;

import static com.aliyun.adb.contest.RaceAnalyticDB.WRITE_BUFFER_SIZE;
import static com.aliyun.adb.contest.RaceAnalyticDB.executorService;

/**
 * @author jingfeng.xjf
 * @date 2021-06-21
 */
public class BucketFile {

    private FileChannel fileChannel;
    private ByteBuffer byteBuffer;
    private long writePosition;
    private int bufferIndex;
    private int bufferSize;

    public BucketFile(String fileName) {
        File file = new File(fileName);
        try {
            file.createNewFile();
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
        writePosition = 0;
        try {
            RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
            fileChannel = randomAccessFile.getChannel();
            byteBuffer = ByteBuffer.allocateDirect(WRITE_BUFFER_SIZE);
            bufferIndex = 0;
            bufferSize = WRITE_BUFFER_SIZE / 8;  // long是8个字节, 这里buffersize指的是能放下的long的数量
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    public void add(long longVal) throws Exception {
        byteBuffer.putLong(longVal);
        bufferIndex++;
        if (bufferIndex != bufferSize) {
            // 分支预测
        } else {
            flush();  // 如果满了, 则刷新
        }
    }

    public void flush() throws Exception {
        if (bufferIndex == 0) {
            return;
        }
        byteBuffer.flip();
        fileChannel.write(byteBuffer, writePosition);
        // 进入了flush函数，说明此时的bufferIndex实际上就是已经落盘的long数量，所以乘8来更新写位置
        writePosition += bufferIndex * 8;
        byteBuffer.clear();
        // 写完之后
        bufferIndex = 0;
    }

    /**
     * 返回桶文件里面的long数量, 等于已经写入的long+还没写入的long
     * @return
     */
    public int getDataNum() {
        return (int)(writePosition / 8) + bufferIndex;
    }

    public Future<Boolean> loadAsync(final long[][] nums, final int[] index) {
        Future<Boolean> future = executorService.submit(() -> {
            byteBuffer.clear();
            // 这个readNo是什么意思
            int readNo = (int)(writePosition / WRITE_BUFFER_SIZE) + (writePosition % WRITE_BUFFER_SIZE == 0 ? 0 : 1);
            long readPosition = 0;
            for (int i = 0; i < readNo; i++) {
                int readSize = 0;
                try {
                    readSize = fileChannel.read(byteBuffer, readPosition);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                readPosition += WRITE_BUFFER_SIZE;
                byteBuffer.flip();
                for (int j = 0; j < readSize / 8; j++) {
                    byteBuffer.position(j * 8);
                    long longVal = byteBuffer.getLong();
                    // 获取分区index p
                    int p = (int)((longVal >> 54) & 0x07);
                    nums[p][index[p]++] = longVal;
                }
                byteBuffer.clear();
            }
            return true;
        });
        return future;
    }

    public void load(long[] nums, int offset) throws Exception {
        byteBuffer.clear();
        int readNo = (int)(writePosition / WRITE_BUFFER_SIZE) + (writePosition % WRITE_BUFFER_SIZE == 0 ? 0 : 1);
        long readPosition = 0;
        int n = offset;
        for (int i = 0; i < readNo; i++) {
            int readSize = fileChannel.read(byteBuffer, readPosition);
            readPosition += WRITE_BUFFER_SIZE;
            byteBuffer.flip();
            for (int j = 0; j < readSize / 8; j++) {
                byteBuffer.position(j * 8);
                nums[n] = byteBuffer.getLong();
                n++;
            }
            byteBuffer.clear();
        }
    }

}
