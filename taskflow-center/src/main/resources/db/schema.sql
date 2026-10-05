-- ============================================================
-- TaskFlow 数据库表结构
-- 数据库：taskflow
-- 字符集：utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS taskflow
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;

USE taskflow;

-- ------------------------------------------------------------
-- 1. 任务表 task
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `task`;
CREATE TABLE `task` (
  `id`          BIGINT       NOT NULL                COMMENT '主键，雪花ID',
  `request_id`  VARCHAR(64)  NOT NULL                COMMENT '幂等键，防重复提交',
  `batch_id`    BIGINT       DEFAULT NULL            COMMENT '所属批次ID',
  `task_type`   VARCHAR(32)  NOT NULL                COMMENT '任务类型(可扩展，平台不解释)',
  `params`      JSON         DEFAULT NULL            COMMENT '任务参数(JSON，内容由业务自定义)',
  `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '状态(封闭集合，平台定义)：0已创建 1已入队 2转写中 3转写完成 4质检中 5成功 6失败 7超时 8已取消',
  `priority`    TINYINT      NOT NULL DEFAULT 1      COMMENT '优先级：10=VIP 1=NORMAL，越大越优先',
  `retry_count` INT          NOT NULL DEFAULT 0      COMMENT '当前重试次数',
  `max_retry`   INT          NOT NULL DEFAULT 3      COMMENT '最大重试次数',
  `fail_reason` VARCHAR(255) DEFAULT NULL            COMMENT '失败原因',
  `result`      TEXT                                 COMMENT '任务结果',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_request_id` (`request_id`),
  KEY `idx_batch_id` (`batch_id`),
  KEY `idx_status_create_time` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务表';

-- ------------------------------------------------------------
-- 2. 批次表 batch
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `batch`;
CREATE TABLE `batch` (
  `id`            BIGINT      NOT NULL             COMMENT '主键，雪花ID',
  `batch_no`      VARCHAR(64) NOT NULL             COMMENT '批次号',
  `total_count`   INT         NOT NULL DEFAULT 0   COMMENT '任务总数',
  `success_count` INT         NOT NULL DEFAULT 0   COMMENT '成功数',
  `failed_count`  INT         NOT NULL DEFAULT 0   COMMENT '失败数',
  `status`        TINYINT     NOT NULL DEFAULT 0   COMMENT '批次状态',
  `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`       TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_no` (`batch_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批次表';

-- ------------------------------------------------------------
-- 3. 本地消息表 local_message
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `local_message`;
CREATE TABLE `local_message` (
  `id`             BIGINT   NOT NULL              COMMENT '主键，雪花ID',
  `task_id`        BIGINT   NOT NULL              COMMENT '关联任务ID',
  `message_status` TINYINT  NOT NULL DEFAULT 0    COMMENT '消息状态：0待发送 1已发送 2发送失败',
  `retry_count`    INT      NOT NULL DEFAULT 0    COMMENT '发送重试次数',
  `create_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`        TINYINT  NOT NULL DEFAULT 0    COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_status_create_time` (`message_status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
