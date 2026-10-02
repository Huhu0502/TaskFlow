package com.taskflow.center;

import com.taskflow.center.entity.Task;
import com.taskflow.center.mapper.TaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class TaskMapperTest {
    @Autowired
    private TaskMapper taskMapper;

    @Test
    void testInsertAndSearch() {
        Task task = new Task();
        task.setRequestId("req-" + System.currentTimeMillis());
        task.setTaskType("SEND_SMS");
        task.setStatus(0);
        task.setPriority(5);

        int rows = taskMapper.insert(task);
        System.out.println("插入行数: " + rows);
        System.out.println("生成的雪花ID: " + task.getId());

        Task db = taskMapper.selectById(task.getId());
        System.out.println("查询结果: " + db);
    }

    @Test
    void testInsertAndSelect() {
        Task task = new Task();
        task.setRequestId("req-" + System.currentTimeMillis());
        task.setTaskType("SEND_SMS");
        task.setStatus(0);
        task.setPriority(5);

        taskMapper.insert(task);

        System.out.println("雪花ID: " + task.getId());
        System.out.println("插入后 createTime: " + task.getCreateTime());   // ⭐ 现在应该有值
        System.out.println("插入后 updateTime: " + task.getUpdateTime());   // ⭐
    }
}
