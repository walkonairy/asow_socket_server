package com.young.asow.modal;

import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class LastMessage {
    Long id;

    String type;

    String content;

    LocalDateTime sendTime;

    Long fromId;
}
