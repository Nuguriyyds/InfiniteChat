package com.wangyutao.contact.controller;

import com.wangyutao.contact.common.Result;
import com.wangyutao.contact.model.dto.AddContactRequest;
import com.wangyutao.contact.model.dto.ContactVO;
import com.wangyutao.contact.model.entity.ContactRequest;
import com.wangyutao.contact.service.ContactService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 联系人控制器
 * 
 * 🌟 核心功能：
 * 1. 添加好友
 * 2. 删除好友
 * 3. 拉黑/取消拉黑
 * 4. 查询联系人列表
 * 5. 搜索联系人
 */
@RestController
@RequestMapping("/api/contact")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    /**
     * 发送好友申请
     */
    @PostMapping("/add")
    public Result<Void> addContact(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @Valid @RequestBody AddContactRequest request) {
        
        request.setUserId(userId);
        contactService.addContact(request);
        return Result.success(null);
    }

    /**
     * 同意好友申请
     */
    @PostMapping("/accept/{requestId}")
    public Result<Void> acceptContact(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PathVariable Long requestId) {
        
        contactService.acceptContact(userId, requestId);
        return Result.success(null);
    }

    /**
     * 拒绝好友申请
     */
    @PostMapping("/reject/{requestId}")
    public Result<Void> rejectContact(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PathVariable Long requestId) {
        
        contactService.rejectContact(userId, requestId);
        return Result.success(null);
    }

    /**
     * 查询收到的好友申请列表
     */
    @GetMapping("/requests")
    public Result<List<ContactRequest>> getPendingRequests(
            @RequestHeader(value = "X-User-Id", required = true) Long userId) {
        
        return Result.success(contactService.getPendingRequests(userId));
    }

    /**
     * 删除好友
     */
    @DeleteMapping("/{contactId}")
    public Result<Void> deleteContact(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PathVariable Long contactId) {
        
        contactService.deleteContact(userId, contactId);
        return Result.success(null);
    }

    /**
     * 拉黑好友
     */
    @PostMapping("/block/{contactId}")
    public Result<Void> blockContact(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PathVariable Long contactId) {
        
        contactService.blockContact(userId, contactId);
        return Result.success(null);
    }

    /**
     * 取消拉黑
     */
    @PostMapping("/unblock/{contactId}")
    public Result<Void> unblockContact(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PathVariable Long contactId) {
        
        contactService.unblockContact(userId, contactId);
        return Result.success(null);
    }

    /**
     * 查询联系人列表
     * 
     * @param contactType 联系人类型（可选，0=普通用户 1=AI助手）
     */
    @GetMapping("/list")
    public Result<List<ContactVO>> getContactList(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @RequestParam(required = false) Integer contactType) {
        
        List<ContactVO> list = contactService.getContactList(userId, contactType);
        return Result.success(list);
    }

    /**
     * 搜索联系人
     */
    @GetMapping("/search")
    public Result<List<ContactVO>> searchContact(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @RequestParam String keyword) {
        
        List<ContactVO> list = contactService.searchContact(userId, keyword);
        return Result.success(list);
    }

    /**
     * 批量校验好友关系（供其他模块 Feign 调用）
     */
    @PostMapping("/checkFriends")
    public Result<Map<Long, Boolean>> checkFriends(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @RequestBody List<Long> contactIds) {
        return Result.success(contactService.checkFriends(userId, contactIds));
    }
}
