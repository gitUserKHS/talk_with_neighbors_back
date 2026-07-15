package com.talkwithneighbors.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class MessageTypeConverter implements AttributeConverter<Message.MessageType, String> {
    @Override
    public String convertToDatabaseColumn(Message.MessageType attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public Message.MessageType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Message.MessageType.valueOf(dbData);
    }
}
