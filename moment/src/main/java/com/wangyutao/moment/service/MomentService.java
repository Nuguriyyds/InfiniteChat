package com.wangyutao.moment.service;

import com.wangyutao.moment.model.dto.CommentMomentRequest;
import com.wangyutao.moment.model.dto.LikeMomentRequest;
import com.wangyutao.moment.model.dto.PublishMomentRequest;
import com.wangyutao.moment.model.vo.MomentVO;

import java.util.List;

public interface MomentService {

    Long publishMoment(Long userId, PublishMomentRequest request);

    void likeMoment(LikeMomentRequest request);

    void unlikeMoment(LikeMomentRequest request);

    void commentMoment(CommentMomentRequest request);

    List<MomentVO> getFriendMoments(Long userId, Integer pageNum, Integer pageSize);

    MomentVO getMomentDetail(Long momentId, Long currentUserId);
}
