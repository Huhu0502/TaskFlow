package com.taskflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.dao.entity.Task;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface TaskMapper extends BaseMapper<Task> {

    /**
     * 条件更新状态（CAS）—— <b>幂等的根基</b>。
     *
     * <p>只有当前状态等于 {@code fromStatus} 时才更新：
     * <ul>
     *   <li>返回 1 = 抢到了，可以继续处理；</li>
     *   <li>返回 0 = 别人已经改过（重复投递、重复消费、已被取消/超时重置），<b>调用方必须放弃</b>。</li>
     * </ul>
     *
     * <p>正因为有它，「至少一次投递 + 重复消费」才是安全的——
     * 补偿扫描、启动恢复、低峰重建都可以放心地重复投递，不用担心任务被重复执行。
     *
     * <p>注意：手写 SQL 不会被 MyBatis-Plus 的逻辑删除插件改写，所以 {@code deleted = 0} 要显式加上；
     * {@code update_time} 也不会走自动填充，用 DB 时钟 {@code NOW()} 保持权威。
     *
     * @return 影响行数（1 = 抢到，0 = 没抢到）
     */
    @Update("UPDATE task SET status = #{toStatus}, update_time = NOW() "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int casStatus(@Param("id") Long id,
                  @Param("fromStatus") int fromStatus,
                  @Param("toStatus") int toStatus);

    /**
     * 带结果的收尾 CAS：阶段执行完毕时一次性推进状态并落结果。
     *
     * <p>{@code result} / {@code failReason} 为 null 时<b>不写该字段</b>，
     * 避免"写失败原因时把已有的转写结果擦掉"。
     *
     * @return 影响行数（1 = 成功收尾，0 = 状态已被其他流程改走，本次结果作废）
     */
    @Update("<script>"
            + "UPDATE task SET status = #{toStatus}, update_time = NOW()"
            + "<if test='result != null'>, result = #{result}</if>"
            + "<if test='failReason != null'>, fail_reason = #{failReason}</if>"
            + " WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0"
            + "</script>")
    int casFinish(@Param("id") Long id,
                  @Param("fromStatus") int fromStatus,
                  @Param("toStatus") int toStatus,
                  @Param("result") String result,
                  @Param("failReason") String failReason);
}
