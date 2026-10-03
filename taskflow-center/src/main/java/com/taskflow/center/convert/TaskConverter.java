package com.taskflow.center.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.center.entity.Task;
import com.taskflow.center.vo.TaskDetailVO;
import com.taskflow.center.vo.TaskListItemVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

@Mapper(componentModel = "spring")
public abstract class TaskConverter {
    @Autowired
    protected ObjectMapper objectMapper;

    @Mapping(target = "params", source = "params", qualifiedByName = "jsonToMap")
    public abstract TaskDetailVO toDetailVO(Task task);

    public abstract TaskListItemVO toListItemVO(Task task);

    @Named("jsonToMap")
    protected Map<String ,Object> JsonToMap(String json) {
        if(json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("params JSON解析失败:" + json, e);
        }
    }
}
