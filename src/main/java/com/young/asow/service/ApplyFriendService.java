package com.young.asow.service;

import com.alibaba.fastjson.JSONObject;
import com.young.asow.entity.*;
import com.young.asow.exception.BusinessException;
import com.young.asow.modal.ConversationModal;
import com.young.asow.modal.FriendApplyModal;
import com.young.asow.modal.MessageModal;
import com.young.asow.repository.*;
import com.young.asow.socket.WebSocketServer;
import com.young.asow.util.ConvertUtil;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ApplyFriendService {

    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
    private final FriendApplyRepository friendApplyRepository;
    private final UserRelationshipRepository userRelationshipRepository;

    public ApplyFriendService(
            ConversationRepository conversationRepository,
            UserRepository userRepository,
            FriendApplyRepository friendApplyRepository,
            UserRelationshipRepository userRelationshipRepository
    ) {
        this.conversationRepository = conversationRepository;
        this.userRepository = userRepository;
        this.friendApplyRepository = friendApplyRepository;
        this.userRelationshipRepository = userRelationshipRepository;
    }


    public List<FriendApplyModal> searchUsers(Long meId, String keyword) {
        List<FriendApplyModal> data = new ArrayList<>();
        Set<Long> processedApplies = new HashSet<>();

        List<User> findUsers = userRepository.findByKeyword(keyword);
        findUsers.forEach(findUser -> {
            FriendApplyModal modal = ConvertUtil.FriendApply2Modal(findUser, null);
            if (Objects.equals(findUser.getId(), meId)) {
                modal.setStatus(FriendApply.STATUS.SELF.name());
                data.add(modal);
                return;
            }

            List<FriendApply> userAcceptApplies = findUser.getAcceptApplies();
            List<FriendApply> userSendApplies = findUser.getSendApplies();

            handleFriendApplies(modal, userAcceptApplies, meId, processedApplies);
            handleFriendApplies(modal, userSendApplies, meId, processedApplies);
            data.add(modal);
        });

        return data;
    }

    private void handleFriendApplies(
            FriendApplyModal modal,
            List<FriendApply> friendApplies,
            Long meId,
            Set<Long> processedApplies
    ) {
        friendApplies.stream()
                .filter(apply -> !processedApplies.contains(apply.getId()))
                .filter(apply ->
                        Objects.equals(apply.getSender().getId(), meId)
                                ||
                                Objects.equals(apply.getAccepter().getId(), meId)
                )
                .forEach(apply -> {
                    if (Objects.equals(apply.getAccepter().getId(), meId)
                            && apply.getStatus().equals(FriendApply.STATUS.APPLYING)
                    ) {
                        modal.setStatus(FriendApply.STATUS.BE_APPLIED.name());
                    } else {
                        modal.setStatus(apply.getStatus().name());
                    }
                    processedApplies.add(apply.getId());
                });
    }


    @Transactional
    public void applyFriend(Long userId, Long accepterId) {
        Assert.isTrue(!Objects.equals(userId, accepterId), "不能添加自己为好友");

        User sender = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("[" + userId + "]" + " is not found"));

        User accepter = userRepository.findById(accepterId)
                .orElseThrow(() -> new BusinessException("[" + accepterId + "]" + " is not found"));

        // 判断向对方是否发送过好友请求
        List<FriendApply> friendApplyList = friendApplyRepository.findFriendApply(userId, accepterId);
        boolean hasApplying = friendApplyList
                .stream()
                .filter(friendApply ->
                        FriendApply.STATUS.APPLYING.equals(friendApply.getStatus()))
                .count() == 1;
        if (hasApplying) {
            throw new BusinessException("用户已经发送过好友请求了");
        }

        // 判断两人是否已经是好友
        List<FriendApply> friendAcceptList = friendApplyRepository.findFriendApply(accepterId, userId);
        boolean hasAccept =
                friendApplyList
                        .stream()
                        .anyMatch(friendApply ->
                                FriendApply.STATUS.ACCEPTED.equals(friendApply.getStatus())) ||
                        friendAcceptList
                                .stream()
                                .anyMatch(friendApply ->
                                        FriendApply.STATUS.ACCEPTED.equals(friendApply.getStatus())
                                );
        if (hasAccept) {
            throw new BusinessException("用户已经添加过该好友了");
        }

        // 如果对方也正在申请添加自己为好友，那么不用等对方通过直接添加好友
        friendApplyRepository.relationship(accepterId, userId)
                .ifPresentOrElse(friendApply -> {
                    if (!FriendApply.STATUS.APPLYING.equals(friendApply.getStatus())) {
                        return;
                    }
                    friendApply.setStatus(FriendApply.STATUS.ACCEPTED);
                    friendApplyRepository.save(friendApply);
                    becomeFriendCreateConversation(sender, accepter);
                }, () -> {
                    FriendApply friendApply = new FriendApply();
                    friendApply.setSender(sender);
                    friendApply.setAccepter(accepter);
                    friendApply.setApplyTime(LocalDateTime.now());
                    friendApply.setOperateTime(LocalDateTime.now());
                    friendApply.setStatus(FriendApply.STATUS.APPLYING);
                    friendApplyRepository.save(friendApply);
                });
    }

    public List<FriendApplyModal> getMyFriendApply(Long userId) {
        return friendApplyRepository.findLatestByAccepterId(userId)
                .stream()
                .map(ac -> ConvertUtil.FriendApply2Modal(ac.getSender(), ac))
                .collect(Collectors.toList());
    }

    @Transactional
    public void handleFriendApply(Long userId, Long senderId, FriendApplyModal modal) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new BusinessException("[" + senderId + "]" + " is not found"));

        User accepter = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("[" + userId + "]" + " is not found"));

        FriendApply friendApply = friendApplyRepository.findApplying(senderId, userId)
                .orElseThrow(() -> new BusinessException("你没有发起过该好友请求"));

        FriendApply.STATUS status = FriendApply.STATUS.valueOf(modal.getStatus());

        if (!Objects.equals(FriendApply.STATUS.ACCEPTED, status) &&
                !Objects.equals(FriendApply.STATUS.REFUSED, status)
        ) {
            throw new BusinessException("参数异常");
        }

        friendApply.setStatus(status);
        friendApply.setOperateTime(LocalDateTime.now());
        friendApplyRepository.save(friendApply);

        if (Objects.equals(FriendApply.STATUS.ACCEPTED, status)) {
            becomeFriendCreateConversation(sender, accepter);
        }
    }


    private void becomeFriendCreateConversation(User sender, User accepter) {
        // 创建好友关系
        createUserRelationship(sender, accepter);
        createUserRelationship(accepter, sender);

        // 创建会话
        Conversation conversation = new Conversation();
        conversation.setFrom(sender);
        conversation.setTo(accepter);
        conversation.setType(Conversation.Type.SINGLE);
        Conversation dbConversation = conversationRepository.save(conversation);

        // 创建未读表
        UserConversation sendConversation = createUserConversation(sender, dbConversation);
        UserConversation acceptConversation = createUserConversation(accepter, dbConversation);

        // 设置未读
        sendConversation.setUnread(1);
        dbConversation.getUserConversations().add(sendConversation);
        dbConversation.getUserConversations().add(acceptConversation);

        // 设置添加好友后的第一句问候语
        Message message = becomeFriendSetTheFirstMessageHello(sender, accepter, dbConversation);
        dbConversation.setLastMessage(message);
        Conversation newConversation = conversationRepository.save(dbConversation);

        // 向前端发送websocket通知建立conversation
        sendWsToClientNotifyCreateConversation(sender, accepter, newConversation);

        // 在conversation中添加第一条消息
        sendWsToClientTheFirstMessageHello(sender, accepter, newConversation, message);
    }

    private void createUserRelationship(User sender, User accepter) {
        UserRelationship userRelationship = new UserRelationship();

        UserRelationshipId userRelationshipId = new UserRelationshipId();
        userRelationshipId.setUserId(sender.getId());
        userRelationshipId.setFriendId(accepter.getId());

        userRelationship.setId(userRelationshipId);
        userRelationship.setUser(sender);
        userRelationship.setFriend(accepter);
        userRelationshipRepository.save(userRelationship);
    }

    private UserConversation createUserConversation(User user, Conversation conversation) {
        UserConversation userConversation = new UserConversation();

        UserConversationId userConversationId = new UserConversationId();
        userConversationId.setConversationId(conversation.getId());
        userConversationId.setUserId(user.getId());

        userConversation.setUser(user);
        userConversation.setId(userConversationId);
        userConversation.setConversation(conversation);
        userConversation.setUnread(0);
        return userConversation;
    }

    private Message becomeFriendSetTheFirstMessageHello(User sender, User accepter, Conversation conversation) {
        Message message = new Message();
        message.setConversation(conversation);
        message.setFrom(accepter);
        message.setTo(sender);
        message.setSendTime(LocalDateTime.now());
        message.setType(Message.ContentType.TEXT);
        message.setContent("U2FsdGVkX18OuyeOgpeXy0CBKf/UjfN0jkInRVu+BcpNvFfGca4NE5B9hDbXUShw39ziyEOEN6kLC6+dW2OPhw==");
        return message;
    }

    private void sendWsToClientNotifyCreateConversation(User sender, User accepter, Conversation conversation) {
        MessageModal modal = new MessageModal();
        modal.setEvent("applyFriend");
        ConversationModal conversationModal = ConvertUtil.Conversation2Modal(conversation, sender, accepter);
        conversationModal.setLastMessage(ConvertUtil.Message2LastMessage(conversation.getLastMessage()));
        modal.setData(conversationModal);
        WebSocketServer.sendMessage(sender.getId(), JSONObject.toJSONString(modal));
        WebSocketServer.sendMessage(accepter.getId(), JSONObject.toJSONString(modal));
    }

    private void sendWsToClientTheFirstMessageHello(User sender, User accepter, Conversation conversation, Message message) {
        MessageModal messageModal = new MessageModal();
        messageModal.setEvent("chat");
        messageModal.setId(conversation.getLastMessage().getId());
        messageModal.setUnread(0);
        messageModal.setContent(message.getContent());
        messageModal.setType(message.getType().name());
        messageModal.setConversationId(conversation.getId());
        messageModal.setFromId(accepter.getId());
        messageModal.setToId(sender.getId());
        messageModal.setSendTime(LocalDateTime.now());
        WebSocketServer.sendMessage(accepter.getId(), JSONObject.toJSONString(messageModal));
        messageModal.setUnread(1);
        WebSocketServer.sendMessage(sender.getId(), JSONObject.toJSONString(messageModal));
    }
}
