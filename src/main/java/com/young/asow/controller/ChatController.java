package com.young.asow.controller;


import com.young.asow.entity.Conversation;
import com.young.asow.modal.ConversationModal;
import com.young.asow.modal.MessageModal;
import com.young.asow.service.ChatService;
import com.young.asow.util.auth.JWTUtil;
import org.springframework.web.bind.annotation.*;
import com.young.asow.response.RestResponse;

import java.util.List;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/conversations")
    public RestResponse<List<ConversationModal>> getCurrentUserConversations(
            @RequestHeader("authorization") String token
    ) {
        String userId = JWTUtil.getUserId(token);
        List<ConversationModal> dbConversations = this.chatService.getConversations(userId);
        return RestResponse.ok(dbConversations);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public RestResponse<List<MessageModal>> getConversationMessages(
            @PathVariable String conversationId
    ) {
        List<MessageModal> modals = chatService.getConversationMessages(conversationId);
        return RestResponse.ok(modals);
    }
}