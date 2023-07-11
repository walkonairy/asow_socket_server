package com.young.asow.socket;

import com.alibaba.fastjson.JSONObject;
import com.young.asow.modal.MessageModal;
import com.young.asow.util.auth.JWTUtil;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
@Service
@ServerEndpoint(value = "/websocket/{token}")
public class WebSocketServer {

    @Autowired
    public static WebSocketService webSocketService;

    private static final long sessionTimeout = 60000;

    // 用来存放每个客户端对应的WebSocketServer对象
    private static final Map<Long, WebSocketServer> webSocketMap = new ConcurrentHashMap<>();

    // 与某个客户端的连接会话，需要通过它来给客户端发送数据
    private Session session;

    // 接收id
    private Long uid;

    private String token;

    // 连接建立成功调用的方法
    @OnOpen
    public void onOpen(Session session, @PathParam("token") String token) {
        Long userId = JWTUtil.getUserId(token);
        log.info("用户Id：" + userId);
        session.setMaxIdleTimeout(sessionTimeout);
        this.session = session;
        this.uid = userId;
        this.token = token;
        if (webSocketMap.containsKey(userId)) {
            webSocketMap.remove(userId);
        }
        webSocketMap.put(userId, this);
        log.info("websocket连接成功编号uid: " + userId + "，当前在线数: " + getOnlineClients());
        MessageModal modal = new MessageModal();
        modal.setContent("websocket连接成功编号uid: " + userId + "，当前在线数: " + getOnlineClients());
        try {
            sendMessage(JSONObject.toJSONString(modal));
        } catch (IOException e) {
            log.error("websocket发送连接成功错误编号uid: " + userId + "，网络异常!!!");
        }
    }

    // 连接关闭调用的方法
    @OnClose
    public void onClose(Session session) throws IOException {
        try (session) {
            if (webSocketMap.containsKey(uid)) {
                webSocketMap.remove(uid);
            }
            log.info("websocket退出编号uid: " + uid + "，当前在线数为: " + getOnlineClients());
        } catch (Exception e) {
            log.error("websocket编号uid连接关闭错误: " + uid + "，原因: " + e.getMessage());
        }
    }

    /**
     * 收到客户端消息后调用的方法
     *
     * @param message 客户端发送过来的消息
     * @param session
     */
    @OnMessage(maxMessageSize = 1024 * 1000)
    public void onMessage(String message, Session session) throws IOException {
        Long userId = JWTUtil.getUserId(token);

        MessageModal client = JSONObject.parseObject(message, MessageModal.class);

        handleMessageWithType(client, session);

        log.info("websocket收到客户端编号uid消息: " + userId);
    }


    private void handleMessageWithType(MessageModal clientMessage, Session session) throws IOException {
        switch (clientMessage.getEvent()) {
            case "heartbeat":
                handlePing();
                break;
            case "chat":
                handleChat(clientMessage);
                break;
            case "notify":
                handleNotify();
                break;
            case "friend_apply":
                handleFriendApply(clientMessage);
                break;
            case "typing":
                handleTyping(clientMessage);
                break;
        }
    }

    private void handlePing() throws IOException {
        MessageModal sm = new MessageModal();
        sm.setType("pong");
        sm.setEvent("heartbeat");
        sm.setContent(LocalDateTime.now().toString());
        sendMessage(JSONObject.toJSONString(sm));
    }

    private void handleChat(MessageModal clientMessage) {
        try {
            // 保存消息到数据库，刷新列表时加载   初步定下来：等保存成功再发送
            Long userId = JWTUtil.getUserId(token);
            MessageModal dbModal = webSocketService.saveMessageWithConversation(clientMessage, userId);

            //  发给自己，可以看作是系统消息
//            session.getBasicRemote().sendText(JSONObject.toJSONString(sm));
            // 发给自己
            clientMessage.setId(dbModal.getId());
            sendMessageByWayBillId(clientMessage.getFromId(), JSONObject.toJSONString(clientMessage));
            Thread.sleep(100);

            // 发给目标
            // 给目标增加未读
            clientMessage.setUnread(dbModal.getUnread());
            sendMessageByWayBillId(clientMessage.getToId(), JSONObject.toJSONString(clientMessage));
        } catch (Exception e) {
            log.error("发送消息发送异常：" + e.getMessage());
        }
    }

    // TODO
    private void handleNotify() {
    }

    private void handleFriendApply(MessageModal clientMessage) {
        // TODO
        Long userId = JWTUtil.getUserId(token);
    }

    private void handleTyping(MessageModal clientMessage) {
        sendMessageByWayBillId(clientMessage.getToId(), JSONObject.toJSONString(clientMessage));
    }

    /**
     * 发生错误时调用
     *
     * @param session
     * @param error
     */
    @OnError
    public void onError(Session session, Throwable error) {
        log.error("websocket编号uid错误: " + this.uid + "原因: " + error.getMessage());
        try {
            MessageModal sm = new MessageModal();
            sm.setEvent("error");
            sm.setContent(error.getMessage());
            sendMessage(JSONObject.toJSONString(sm));
        } catch (IOException e) {
            log.error("发送错误消息时出现异常: " + e.getMessage());
        }
        error.printStackTrace();
    }

    /**
     * 单机使用，外部接口通过指定的客户id向该客户推送消息
     *
     * @param key
     * @param message
     * @return boolean
     */
    public static synchronized boolean sendMessageByWayBillId(@NonNull Long key, String message) {
        WebSocketServer webSocketServer = webSocketMap.get(key);
        if (Objects.nonNull(webSocketServer)) {
            try {
                webSocketServer.session.getBasicRemote().sendText(message);
//                webSocketServer.sendMessage(message);
                log.info("websocket发送消息编号uid为: " + key + "发送消息: " + message);
                return true;
            } catch (Exception e) {
                log.error("websocket发送消息失败编号uid为: " + key + "消息: " + message);
                return false;
            }
        } else {
            log.error("websocket未连接编号uid号为: " + key + "消息: " + message);
            return false;
        }
    }

    // 群发自定义消息
    public static void sendInfo(String message) {
        webSocketMap.forEach((k, v) -> {
            WebSocketServer webSocketServer = webSocketMap.get(k);
            try {
                webSocketServer.sendMessage(message);
                log.info("websocket群发消息编号uid为: " + k + "，消息: " + message);
            } catch (IOException e) {
                log.error("群发自定义消息失败: " + k + "，message: " + message);
            }
        });
    }

    /**
     * 服务端群发消息-心跳包
     *
     * @param message
     * @return int
     */
    public static synchronized int sendPing(String message) {
        if (webSocketMap.size() <= 0) {
            return 0;
        }
        StringBuffer uids = new StringBuffer();
        AtomicInteger count = new AtomicInteger();
        webSocketMap.forEach((uid, server) -> {
            count.getAndIncrement();
            if (webSocketMap.containsKey(uid)) {
                WebSocketServer webSocketServer = webSocketMap.get(uid);
                try {
                    webSocketServer.sendMessage(message);
                    if (count.equals(webSocketMap.size() - 1)) {
                        uids.append("uid");
                        return; // 跳出本次循环
                    }
                    uids.append(uid).append(",");
                } catch (IOException e) {
                    webSocketMap.remove(uid);
                    log.info("客户端心跳检测异常移除: " + uid + "，心跳发送失败，已移除！");
                }
            } else {
                log.info("客户端心跳检测异常不存在: " + uid + "，不存在！");
            }
        });
        log.info("客户端心跳检测结果: " + uids + "连接正在运行");
        return webSocketMap.size();
    }

    // 实现服务器主动推送
    public void sendMessage(String message) throws IOException {
        synchronized (this.session) {
            this.session.getAsyncRemote().sendText(message);
        }
    }


    // 获取客户端在线数
    public static synchronized int getOnlineClients() {
        if (Objects.isNull(webSocketMap)) {
            return 0;
        } else {
            return webSocketMap.size();
        }
    }

    /**
     * 连接是否存在
     *
     * @param uid
     * @return boolean
     */
    public static boolean isConnected(String uid) {
        return Objects.nonNull(webSocketMap) && webSocketMap.containsKey(uid);
    }
}
