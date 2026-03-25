package com.wangyutao.contact.service;

import com.wangyutao.contact.model.dto.AddContactRequest;
import com.wangyutao.contact.model.dto.ContactVO;
import com.wangyutao.contact.model.entity.ContactRequest;

import java.util.List;
import java.util.Map;

public interface ContactService {
    
    /**
     * 发送好友申请
     */
    void addContact(AddContactRequest request);

    /**
     * 同意好友申请
     */
    void acceptContact(Long userId, Long requestId);

    /**
     * 拒绝好友申请
     */
    void rejectContact(Long userId, Long requestId);

    /**
     * 查询收到的好友申请列表
     */
    List<ContactRequest> getPendingRequests(Long userId);
    
    void deleteContact(Long userId, Long contactId);
    
    void blockContact(Long userId, Long contactId);
    
    void unblockContact(Long userId, Long contactId);
    
    List<ContactVO> getContactList(Long userId, Integer contactType);
    
    List<ContactVO> searchContact(Long userId, String keyword);
    
    void createAiAssistant(Long userId);

    /**
     * 批量校验好友关系
     * @return key=contactId, value=是否为好友
     */
    Map<Long, Boolean> checkFriends(Long userId, List<Long> contactIds);
}
