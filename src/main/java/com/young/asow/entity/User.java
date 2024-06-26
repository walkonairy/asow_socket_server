package com.young.asow.entity;

import lombok.*;
import lombok.experimental.FieldDefaults;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity(name = "user")
@Getter
@Setter
//@ToString
@FieldDefaults(level = AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {

    public static Long sysId = 999999999L;

    @Column
    @NonNull
    String username;

    @Column
    String nickname;

    @Column
    @NonNull
    String password;

    @Column
    @NonNull
    String email;

    @Column
    String avatar;

    @Column
    LocalDateTime lastLoginTime;

    @ElementCollection(fetch = FetchType.EAGER)
    Set<Authority> authorities = new HashSet<>();

    public void addAuthority(Authority authority) {
        authorities.add(authority);
    }

//    @OneToMany(mappedBy = "from", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
//    private List<Message> fromMessages = new ArrayList<>();
//
//    @OneToMany(mappedBy = "to", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
//    private List<Message> toMessages = new ArrayList<>();

    @OneToMany(mappedBy = "sender", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    private List<FriendApply> sendApplies = new ArrayList<>();

    @OneToMany(mappedBy = "accepter", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    private List<FriendApply> acceptApplies = new ArrayList<>();


    @OneToMany(mappedBy = "user")
    private Set<UserConversation> userConversations = new HashSet<>();


    @ManyToMany
    @JoinTable(
            name = "chat_group_user",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "chat_group_id")
    )
    private Set<ChatGroup> chatGroups = new HashSet<>();
}
