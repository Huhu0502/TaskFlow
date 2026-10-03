package com.taskflow.common.result;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {
    private Long pageNo;
    private Long pageSize;
    private Long total;
    private List<T> records;

    public static <T> PageResult<T> of(long pageNo, long pageSize, long total, List<T> records){
        PageResult<T> r = new PageResult<>();
        r.pageSize = pageSize;
        r.pageNo = pageNo;
        r.total = total;
        r.records = records;
        return r;
    }
}
