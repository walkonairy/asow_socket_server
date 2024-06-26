package com.young.asow.socket;

import com.young.asow.modal.MessageModal;
import com.young.asow.service.ChatService;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class WebSocketService {
    private final ChatService chatService;

    public WebSocketService(ChatService chatService) {
        this.chatService = chatService;
    }


    public List<MessageModal> saveMessageWithConversation(MessageModal message, Long fromId) {
        return chatService.saveMessageWithConversation(message, fromId);
    }
}
