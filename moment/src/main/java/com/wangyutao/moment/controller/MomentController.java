package com.wangyutao.moment.controller;

import com.wangyutao.moment.common.Result;
import com.wangyutao.moment.common.ResultGenerator;
import com.wangyutao.moment.model.dto.CommentMomentRequest;
import com.wangyutao.moment.model.dto.LikeMomentRequest;
import com.wangyutao.moment.model.dto.PublishMomentRequest;
import com.wangyutao.moment.model.vo.MomentVO;
import com.wangyutao.moment.service.MomentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/moment")
@RequiredArgsConstructor
public class MomentController {

    private final MomentService momentService;

    @PostMapping("/publish")
    public Result<Long> publishMoment(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @RequestBody PublishMomentRequest request) {
        Long momentId = momentService.publishMoment(userId, request);
        return ResultGenerator.genSuccessResult(momentId);
    }

    @PostMapping("/like")
    public Result<Void> likeMoment(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @RequestBody LikeMomentRequest request) {
        request.setUserId(userId);
        momentService.likeMoment(request);
        return ResultGenerator.genSuccessResult();
    }

    @PostMapping("/unlike")
    public Result<Void> unlikeMoment(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @RequestBody LikeMomentRequest request) {
        request.setUserId(userId);
        momentService.unlikeMoment(request);
        return ResultGenerator.genSuccessResult();
    }

    @PostMapping("/comment")
    public Result<Void> commentMoment(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @RequestBody CommentMomentRequest request) {
        request.setUserId(userId);
        momentService.commentMoment(request);
        return ResultGenerator.genSuccessResult();
    }

    @GetMapping("/friends")
    public Result<List<MomentVO>> getFriendMoments(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        List<MomentVO> moments = momentService.getFriendMoments(userId, pageNum, pageSize);
        return ResultGenerator.genSuccessResult(moments);
    }

    @GetMapping("/detail/{momentId}")
    public Result<MomentVO> getMomentDetail(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PathVariable Long momentId) {
        MomentVO moment = momentService.getMomentDetail(momentId, userId);
        return ResultGenerator.genSuccessResult(moment);
    }
}
