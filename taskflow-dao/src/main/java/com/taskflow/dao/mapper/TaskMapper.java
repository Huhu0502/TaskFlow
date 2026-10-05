package com.taskflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.dao.entity.Task;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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

    /**
     * 只刷新 {@code update_time}（心跳）。
     *
     * <p>用于「已经做了动作但状态不变」的场景：补偿扫描把 TRANSCRIBED 任务重新投进质检入口桶后，
     * 状态仍是 TRANSCRIBED，若不刷新 {@code update_time}，下一轮补偿会把同一条又扫出来重复投递。
     * 补偿扫描以 {@code update_time} 为「停滞时长」依据，所以这里充当去重闸门。
     */
    @Update("UPDATE task SET update_time = NOW() WHERE id = #{id} AND deleted = 0")
    int touch(@Param("id") Long id);

    /**
     * 按状态计数。
     *
     * <p>用于「数量对账」：DB 里 {@code QUEUED} 的数量不应超过「内存队列元素总数 + 在途任务上限」。
     * 超出就说明一定有任务丢了。
     *
     * <p>属于全索引扫描，只应在低频场景（重建前对账）调用，不要放到热路径。
     */
    @Select("SELECT COUNT(*) FROM task WHERE status = #{status} AND deleted = 0")
    long countByStatus(@Param("status") int status);
}
