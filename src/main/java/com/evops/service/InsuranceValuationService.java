package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.PermissionClosedException;
import com.evops.dto.ValuationRegisterRequest;
import com.evops.dto.ValuationReviewRequest;
import com.evops.dto.ValuationReviseRequest;
import com.evops.entity.InsuranceValuation;
import com.evops.entity.InsuranceValuationLoanBind;
import com.evops.entity.InsuranceValuationReminder;
import com.evops.entity.InsuranceValuationVersion;
import com.evops.entity.InsuranceValuationVoucher;
import com.evops.entity.Relic;
import com.evops.mapper.InsuranceValuationLoanBindMapper;
import com.evops.mapper.InsuranceValuationMapper;
import com.evops.mapper.InsuranceValuationReminderMapper;
import com.evops.mapper.InsuranceValuationVersionMapper;
import com.evops.mapper.InsuranceValuationVoucherMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物保险估值管理（museum-15）。
 *
 * <p>核心规则：</p>
 * <ol>
 *   <li>一件文物一份估值主单；来源/评估机构/币种/有效期随版本固化。
 *       V1 登记即生效；金额修订必须生成新版本并关联保险批单，旧生效版本置
 *       SUPERSEDED，只追加不改写。</li>
 *   <li>修订双人审批：复核人必须与提出人不同，通过后新版本才生效。</li>
 *   <li>借展审批通过（签约）时把当时生效版本固化为借展绑定，借展期间
 *       出库/归还原口径一律沿用绑定版本，估值随后修订或过期都不改写。</li>
 *   <li>有效期临期/过期只产生提醒，<b>不冻结借展出库，更不冻结归还</b>。</li>
 *   <li>字段级访问：保险管理员见全部；财务只见金额/币种/有效期与凭证；
 *       公众与普通馆员拒绝访问。</li>
 *   <li>所有变更动作先过 {@link PermissionService#requireValuationChangeOpen()}
 *       服务入口闸门——缺权收紧时换任何调用地址都在入口拒绝。</li>
 * </ol>
 */
@Service
public class InsuranceValuationService {

    /** 财务视角可见的版本字段白名单：金额、币种、有效期、凭证与生命周期状态 */
    private static final Set<String> FINANCE_VERSION_KEYS = new HashSet<>(Arrays.asList(
            "id", "versionNo", "status", "amount", "currency",
            "validFrom", "validUntil", "supersededAt", "vouchers"));

    private final InsuranceValuationMapper valuationMapper;
    private final InsuranceValuationVersionMapper versionMapper;
    private final InsuranceValuationVoucherMapper voucherMapper;
    private final InsuranceValuationLoanBindMapper bindMapper;
    private final InsuranceValuationReminderMapper reminderMapper;
    private final RelicService relicService;
    private final PermissionService permissionService;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;

    public InsuranceValuationService(InsuranceValuationMapper valuationMapper,
                                     InsuranceValuationVersionMapper versionMapper,
                                     InsuranceValuationVoucherMapper voucherMapper,
                                     InsuranceValuationLoanBindMapper bindMapper,
                                     InsuranceValuationReminderMapper reminderMapper,
                                     RelicService relicService,
                                     PermissionService permissionService,
                                     LedgerService ledgerService,
                                     ObjectMapper objectMapper) {
        this.valuationMapper = valuationMapper;
        this.versionMapper = versionMapper;
        this.voucherMapper = voucherMapper;
        this.bindMapper = bindMapper;
        this.reminderMapper = reminderMapper;
        this.relicService = relicService;
        this.permissionService = permissionService;
        this.ledgerService = ledgerService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 初始登记
    // ------------------------------------------------------------------

    /**
     * 初始登记估值：一件文物一份，V1 登记即 APPROVED 生效（首次估值由登记人负责，
     * 不属于"金额修订"，无需双人审批；此后修订才必须双人审批）。
     */
    @Transactional
    public InsuranceValuation register(Long relicId, ValuationRegisterRequest req) {
        permissionService.requireValuationChangeOpen();
        Relic relic = relicService.requireActiveRelic(relicId);
        Long exists = valuationMapper.selectCount(new LambdaQueryWrapper<InsuranceValuation>()
                .eq(InsuranceValuation::getRelicId, relicId));
        if (exists != null && exists > 0) {
            throw new IllegalArgumentException("该文物已存在保险估值记录，金额变化必须走修订新版本: "
                    + relic.getRelicNo());
        }
        validatePeriod(req.getValidFrom(), req.getValidUntil());

        LocalDateTime now = LocalDateTime.now();
        InsuranceValuation valuation = new InsuranceValuation();
        valuation.setValuationNo("IV-" + String.format("%06d", valuationMapper.nextValuationNo()));
        valuation.setRelicId(relicId);
        valuation.setStatus(IVAL_ACTIVE);
        valuation.setRegisteredBy(req.getOperator());
        valuation.setRegisteredAt(now);
        valuationMapper.insert(valuation);

        InsuranceValuationVersion v1 = new InsuranceValuationVersion();
        v1.setValuationId(valuation.getId());
        v1.setVersionNo(1);
        v1.setStatus(IVAL_APPROVED);
        fillVersionFields(v1, req.getAmount(), req.getCurrency(), req.getValuationSource(),
                req.getAppraiserOrg(), req.getAppraiserName(), req.getReportNo(),
                req.getValidFrom(), req.getValidUntil(), req.getBasis());
        v1.setProposerName(req.getOperator());
        v1.setProposedAt(now);
        v1.setReviewedAt(now);
        v1.setReviewOpinion("初始登记，V1 登记即生效");
        versionMapper.insert(v1);

        valuation.setCurrentVersionId(v1.getId());
        valuationMapper.updateById(valuation);

        insertVouchers(v1.getId(), req.getVouchers(), req.getOperator(), now);

        ledgerService.append(relicId, null, null, ACTION_IVAL_REGISTER, valuation.getValuationNo(),
                "保险估值初始登记 V1 生效，金额 " + v1.getAmount() + " " + v1.getCurrency()
                        + "，来源：" + v1.getValuationSource()
                        + "，评估机构：" + v1.getAppraiserOrg()
                        + "，有效期至 " + v1.getValidUntil()
                        + "，凭证 " + req.getVouchers().size() + " 张，登记人：" + req.getOperator(),
                now);
        return valuation;
    }

    // ------------------------------------------------------------------
    // 金额修订（新版本 + 保险批单，双人审批）
    // ------------------------------------------------------------------

    /**
     * 提出金额修订：生成 PENDING_APPROVAL 新版本并关联保险批单，旧版本保持生效，
     * 待与提出人不同的复核人审批通过后才切换。
     */
    @Transactional
    public InsuranceValuationVersion revise(Long relicId, ValuationReviseRequest req) {
        permissionService.requireValuationChangeOpen();
        InsuranceValuation valuation = requireValuationByRelic(relicId);
        Long pending = versionMapper.selectCount(new LambdaQueryWrapper<InsuranceValuationVersion>()
                .eq(InsuranceValuationVersion::getValuationId, valuation.getId())
                .eq(InsuranceValuationVersion::getStatus, IVAL_PENDING_APPROVAL));
        if (pending != null && pending > 0) {
            throw new IllegalArgumentException("存在待双人复核的估值修订版本，不能重复提出: "
                    + valuation.getValuationNo());
        }
        validatePeriod(req.getValidFrom(), req.getValidUntil());

        Integer maxNo = versionMapper.selectList(new LambdaQueryWrapper<InsuranceValuationVersion>()
                        .eq(InsuranceValuationVersion::getValuationId, valuation.getId())
                        .orderByDesc(InsuranceValuationVersion::getVersionNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(InsuranceValuationVersion::getVersionNo).orElse(0);

        LocalDateTime now = LocalDateTime.now();
        InsuranceValuationVersion version = new InsuranceValuationVersion();
        version.setValuationId(valuation.getId());
        version.setVersionNo(maxNo + 1);
        version.setStatus(IVAL_PENDING_APPROVAL);
        fillVersionFields(version, req.getAmount(), req.getCurrency(), req.getValuationSource(),
                req.getAppraiserOrg(), req.getAppraiserName(), req.getReportNo(),
                req.getValidFrom(), req.getValidUntil(), req.getBasis());
        // 金额修订必须关联保险批单
        version.setEndorsementNo(req.getEndorsementNo());
        version.setEndorsementIssuedAt(req.getEndorsementIssuedAt());
        version.setEndorsementNote(req.getEndorsementNote());
        version.setProposerName(req.getProposer());
        version.setProposedAt(now);
        versionMapper.insert(version);
        insertVouchers(version.getId(), req.getVouchers(), req.getProposer(), now);

        ledgerService.append(relicId, null, null, ACTION_IVAL_REVISE, valuation.getValuationNo(),
                "保险估值金额修订提出 V" + version.getVersionNo() + "（待双人复核），新金额 "
                        + version.getAmount() + " " + version.getCurrency()
                        + "，关联保险批单：" + version.getEndorsementNo()
                        + (version.getEndorsementNote() == null ? "" : "（" + version.getEndorsementNote() + "）")
                        + "，提出人：" + req.getProposer(), now);
        return version;
    }

    /**
     * 双人审批金额修订。通过：新版本 APPROVED 生效、旧生效版本 SUPERSEDED；
     * 驳回：版本封存 REJECTED 只读。复核人必须与提出人不同，同人复核一律拒绝。
     */
    @Transactional
    public InsuranceValuationVersion review(Long versionId, ValuationReviewRequest req) {
        permissionService.requireValuationChangeOpen();
        InsuranceValuationVersion version = requireVersion(versionId);
        if (!IVAL_PENDING_APPROVAL.equals(version.getStatus())) {
            throw new IllegalArgumentException("该估值版本不处于待复核状态，不能审批: V"
                    + version.getVersionNo() + "（" + version.getStatus() + "）");
        }
        String decision = req.getDecision();
        if (!IVAL_REVIEW_APPROVE.equals(decision) && !IVAL_REVIEW_REJECT.equals(decision)) {
            throw new IllegalArgumentException("复核决定只能为 APPROVE/REJECT");
        }
        // 双人审批：复核人不得与提出人为同一人
        if (req.getReviewer().equals(version.getProposerName())) {
            throw new IllegalArgumentException("金额修订必须双人审批：复核人不能与修订提出人为同一人（"
                    + version.getProposerName() + "）");
        }
        boolean reject = IVAL_REVIEW_REJECT.equals(decision);
        if (reject && (req.getOpinion() == null || req.getOpinion().trim().isEmpty())) {
            throw new IllegalArgumentException("驳回复核必须填写意见");
        }

        LocalDateTime now = LocalDateTime.now();
        version.setReviewerName(req.getReviewer());
        version.setReviewedAt(now);
        version.setReviewOpinion(req.getOpinion());
        InsuranceValuation valuation = requireValuation(version.getValuationId());

        if (reject) {
            version.setStatus(IVAL_REJECTED);
            versionMapper.updateById(version);
            ledgerService.append(valuation.getRelicId(), null, null,
                    ACTION_IVAL_REVIEW_REJECT, valuation.getValuationNo(),
                    "保险估值修订 V" + version.getVersionNo() + " 双人复核被驳回（版本封存只读），复核人："
                            + req.getReviewer() + "，意见：" + req.getOpinion(), now);
            return version;
        }

        // 通过：旧生效版本置 SUPERSEDED（只追加不改写），切换当前版本指针
        InsuranceValuationVersion previous = valuation.getCurrentVersionId() == null ? null
                : versionMapper.selectById(valuation.getCurrentVersionId());
        if (previous != null && IVAL_APPROVED.equals(previous.getStatus())) {
            previous.setStatus(IVAL_SUPERSEDED);
            previous.setSupersededAt(now);
            versionMapper.updateById(previous);
        }
        version.setStatus(IVAL_APPROVED);
        versionMapper.updateById(version);
        valuation.setCurrentVersionId(version.getId());
        valuationMapper.updateById(valuation);

        // 旧版本未闭环的临期/过期提醒随新版本生效自动闭环（不删除，只置 RESOLVED 留痕）
        resolveOpenReminders(previous == null ? null : previous.getId(),
                req.getReviewer(), "估值新版本 V" + version.getVersionNo() + " 生效，旧版本提醒自动闭环", now);

        ledgerService.append(valuation.getRelicId(), null, null,
                ACTION_IVAL_REVIEW_APPROVE, valuation.getValuationNo(),
                "保险估值修订 V" + version.getVersionNo() + " 双人复核通过并生效，金额 "
                        + version.getAmount() + " " + version.getCurrency()
                        + "，关联保险批单：" + version.getEndorsementNo()
                        + "，提出人：" + version.getProposerName()
                        + "，复核人：" + req.getReviewer()
                        + (previous == null ? "" : "；旧 V" + previous.getVersionNo() + " 已置 SUPERSEDED"),
                now);
        return version;
    }

    // ------------------------------------------------------------------
    // 借展签约快照绑定：借展期间沿用签约时版本
    // ------------------------------------------------------------------

    /**
     * 借阅审批全部通过（签约生成借阅快照）时调用：把当前生效估值版本固化绑定。
     * 一件申请只绑定一次；文物尚未建立估值记录时不阻断借阅（借阅快照自身仍固化
     * 申请口径保险金额），返回 null。
     */
    @Transactional
    public InsuranceValuationLoanBind bindOnLoanApproved(Long applicationId, Long snapshotId,
                                                          Long relicId, LocalDateTime now) {
        InsuranceValuation valuation = valuationMapper.selectOne(
                new LambdaQueryWrapper<InsuranceValuation>()
                        .eq(InsuranceValuation::getRelicId, relicId));
        if (valuation == null) {
            return null;
        }
        InsuranceValuationVersion current = valuation.getCurrentVersionId() == null ? null
                : versionMapper.selectById(valuation.getCurrentVersionId());
        if (current == null || !IVAL_APPROVED.equals(current.getStatus())) {
            // 没有生效版本（理论上不会发生：登记即有 V1）不绑定，也不阻断签约
            return null;
        }
        InsuranceValuationLoanBind existed = bindMapper.selectOne(
                new LambdaQueryWrapper<InsuranceValuationLoanBind>()
                        .eq(InsuranceValuationLoanBind::getApplicationId, applicationId));
        if (existed != null) {
            return existed;
        }
        InsuranceValuationLoanBind bind = new InsuranceValuationLoanBind();
        bind.setValuationId(valuation.getId());
        bind.setValuationVersionId(current.getId());
        bind.setApplicationId(applicationId);
        bind.setSnapshotId(snapshotId);
        bind.setRelicId(relicId);
        bind.setBoundAt(now == null ? LocalDateTime.now() : now);
        bindMapper.insert(bind);
        ledgerService.append(relicId, null, null, ACTION_IVAL_LOAN_BIND,
                valuation.getValuationNo(),
                "借展签约固化保险估值口径：绑定 V" + current.getVersionNo()
                        + "（金额 " + current.getAmount() + " " + current.getCurrency()
                        + "，批单 " + current.getEndorsementNo() + "），借阅申请 id=" + applicationId
                        + "；借展期间出库/归还沿用该签约时快照，不随后续修订或过期改写",
                bind.getBoundAt());
        return bind;
    }

    // ------------------------------------------------------------------
    // 查询（字段级访问控制）
    // ------------------------------------------------------------------

    /** 估值详情：按岗位脱敏；公众与普通馆员拒绝访问敏感字段 */
    public Map<String, Object> viewByRelic(Long relicId, String role) {
        String viewerRole = requireViewerRole(role);
        relicService.requireRelic(relicId);
        InsuranceValuation valuation = requireValuationByRelic(relicId);

        List<InsuranceValuationVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<InsuranceValuationVersion>()
                        .eq(InsuranceValuationVersion::getValuationId, valuation.getId())
                        .orderByDesc(InsuranceValuationVersion::getVersionNo));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valuation", toMap(valuation));
        List<Map<String, Object>> versionViews = new ArrayList<>();
        Map<String, Object> currentView = null;
        for (InsuranceValuationVersion v : versions) {
            Map<String, Object> view = maskVersion(v, viewerRole);
            versionViews.add(view);
            if (v.getId().equals(valuation.getCurrentVersionId())) {
                currentView = view;
            }
        }
        result.put("current", currentView);
        result.put("versions", versionViews);
        // 有效期提醒不属于敏感金额字段，但只对有估值访问权的岗位开放
        result.put("openReminders", toMap(listOpenReminders(valuation.getId())));
        return result;
    }

    /**
     * 借展沿用的签约时估值口径：返回绑定版本（而非当前版本）。
     * 借展期间估值已修订出新版本时，这里仍是签约时的 V?。
     */
    public Map<String, Object> viewLoanBinding(Long applicationId, String role) {
        String viewerRole = requireViewerRole(role);
        InsuranceValuationLoanBind bind = bindMapper.selectOne(
                new LambdaQueryWrapper<InsuranceValuationLoanBind>()
                        .eq(InsuranceValuationLoanBind::getApplicationId, applicationId));
        if (bind == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("bound", false);
            empty.put("message", "该借阅申请签约时文物尚无保险估值记录，口径以借阅快照保险金额为准");
            return empty;
        }
        InsuranceValuationVersion bound = requireVersion(bind.getValuationVersionId());
        InsuranceValuationVersion current = versionMapper.selectById(
                requireValuation(bind.getValuationId()).getCurrentVersionId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bound", true);
        result.put("bind", toMap(bind));
        result.put("boundVersion", maskVersion(bound, viewerRole));
        // 当前生效版本仅供对照；借展出库/归还口径仍以 boundVersion 为准
        result.put("currentVersionNo", current == null ? null : current.getVersionNo());
        result.put("followsSignSnapshot",
                current == null || current.getId().equals(bind.getValuationVersionId()));
        return result;
    }

    /** 临期/过期提醒列表：仅保险管理员与财务（有效期口径）可看 */
    public List<InsuranceValuationReminder> listReminders(String role, String status) {
        requireViewerRole(role);
        LambdaQueryWrapper<InsuranceValuationReminder> wrapper =
                new LambdaQueryWrapper<InsuranceValuationReminder>()
                        .orderByDesc(InsuranceValuationReminder::getLastRaisedAt);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(InsuranceValuationReminder::getStatus, status);
        }
        return reminderMapper.selectList(wrapper);
    }

    // ------------------------------------------------------------------
    // 有效期提醒扫描（只提醒，不冻结出库/归还）
    // ------------------------------------------------------------------

    /**
     * 扫描一轮临期/过期提醒，返回本轮触达（新建或重复提醒）的条数。
     * 按 (估值, 版本, 类型) 幂等；只针对当前生效版本；
     * 本方法<b>不修改任何借展/归还状态</b>。
     */
    @Transactional
    public int scanReminders(LocalDateTime now, int expiryRemindDays) {
        if (now == null) {
            now = LocalDateTime.now();
        }
        int touched = 0;
        List<InsuranceValuation> valuations = valuationMapper.selectList(
                new LambdaQueryWrapper<InsuranceValuation>()
                        .eq(InsuranceValuation::getStatus, IVAL_ACTIVE));
        for (InsuranceValuation valuation : valuations) {
            if (valuation.getCurrentVersionId() == null) {
                continue;
            }
            InsuranceValuationVersion current = versionMapper.selectById(
                    valuation.getCurrentVersionId());
            if (current == null || !IVAL_APPROVED.equals(current.getStatus())) {
                continue;
            }
            String type = null;
            if (!now.isBefore(current.getValidUntil())) {
                type = IVAL_REMIND_EXPIRED;
            } else if (expiryRemindDays > 0
                    && !now.plusDays(expiryRemindDays).isBefore(current.getValidUntil())) {
                type = IVAL_REMIND_EXPIRING;
            }
            if (type != null) {
                raiseReminder(valuation, current, type, now);
                touched++;
            }
        }
        return touched;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private void raiseReminder(InsuranceValuation valuation,
                               InsuranceValuationVersion version, String type, LocalDateTime now) {
        InsuranceValuationReminder reminder = reminderMapper.selectOne(
                new LambdaQueryWrapper<InsuranceValuationReminder>()
                        .eq(InsuranceValuationReminder::getValuationId, valuation.getId())
                        .eq(InsuranceValuationReminder::getVersionId, version.getId())
                        .eq(InsuranceValuationReminder::getType, type));
        if (reminder == null) {
            reminder = new InsuranceValuationReminder();
            reminder.setRemindNo("IR-" + String.format("%06d", reminderMapper.nextReminderNo()));
            reminder.setValuationId(valuation.getId());
            reminder.setVersionId(version.getId());
            reminder.setType(type);
            reminder.setStatus(IVAL_REMIND_OPEN);
            reminder.setFirstRaisedAt(now);
            reminder.setLastRaisedAt(now);
            reminder.setRaisedCount(1);
            reminderMapper.insert(reminder);
            ledgerService.append(valuation.getRelicId(), null, null, ACTION_IVAL_REMIND,
                    reminder.getRemindNo(),
                    (IVAL_REMIND_EXPIRED.equals(type) ? "保险估值已过期" : "保险估值临期")
                            + "提醒：估值单 " + valuation.getValuationNo() + " V" + version.getVersionNo()
                            + "，有效期至 " + version.getValidUntil()
                            + "（仅提醒，不冻结借展出库与归还）", now);
        } else if (IVAL_REMIND_OPEN.equals(reminder.getStatus())) {
            reminder.setLastRaisedAt(now);
            reminder.setRaisedCount((reminder.getRaisedCount() == null ? 0 : reminder.getRaisedCount()) + 1);
            reminderMapper.updateById(reminder);
        }
    }

    private void resolveOpenReminders(Long versionId, String resolvedBy, String note, LocalDateTime now) {
        if (versionId == null) {
            return;
        }
        List<InsuranceValuationReminder> open = reminderMapper.selectList(
                new LambdaQueryWrapper<InsuranceValuationReminder>()
                        .eq(InsuranceValuationReminder::getVersionId, versionId)
                        .eq(InsuranceValuationReminder::getStatus, IVAL_REMIND_OPEN));
        for (InsuranceValuationReminder r : open) {
            r.setStatus(IVAL_REMIND_RESOLVED);
            r.setResolvedBy(resolvedBy);
            r.setResolvedAt(now);
            r.setResolveNote(note);
            reminderMapper.updateById(r);
        }
    }

    private List<InsuranceValuationReminder> listOpenReminders(Long valuationId) {
        return reminderMapper.selectList(new LambdaQueryWrapper<InsuranceValuationReminder>()
                .eq(InsuranceValuationReminder::getValuationId, valuationId)
                .eq(InsuranceValuationReminder::getStatus, IVAL_REMIND_OPEN)
                .orderByAsc(InsuranceValuationReminder::getType));
    }

    /** 读访问控制：保险管理员/财务放行；公众与普通馆员拒绝访问敏感字段 */
    private String requireViewerRole(String role) {
        if (!ROLE_INSURANCE_ADMIN.equals(role) && !ROLE_FINANCE.equals(role)) {
            throw new PermissionClosedException(
                    "无权访问文物保险估值敏感信息：公众与普通馆员不可访问估值金额、来源、"
                            + "评估机构、币种、有效期与凭证（当前岗位："
                            + (role == null || role.isEmpty() ? "未声明" : role) + "）");
        }
        return role;
    }

    private Map<String, Object> maskVersion(InsuranceValuationVersion version, String viewerRole) {
        Map<String, Object> map = toMap(version);
        List<InsuranceValuationVoucher> vouchers = voucherMapper.selectList(
                new LambdaQueryWrapper<InsuranceValuationVoucher>()
                        .eq(InsuranceValuationVoucher::getVersionId, version.getId())
                        .orderByAsc(InsuranceValuationVoucher::getId));
        map.put("vouchers", toMap(vouchers));
        if (ROLE_FINANCE.equals(viewerRole)) {
            // 财务只见金额、币种、有效期与凭证；来源/评估机构/批单/签署人等字段全部剥离
            map.keySet().retainAll(FINANCE_VERSION_KEYS);
        }
        return map;
    }

    private void insertVouchers(Long versionId, List<ValuationRegisterRequest.VoucherItem> items,
                                String operator, LocalDateTime now) {
        for (ValuationRegisterRequest.VoucherItem item : items) {
            InsuranceValuationVoucher voucher = new InsuranceValuationVoucher();
            voucher.setVersionId(versionId);
            voucher.setFileName(item.getFileName());
            voucher.setVoucherRef(item.getVoucherRef());
            voucher.setNote(item.getNote());
            voucher.setUploadedBy(operator);
            voucher.setUploadedAt(now);
            voucherMapper.insert(voucher);
        }
    }

    private void fillVersionFields(InsuranceValuationVersion v, java.math.BigDecimal amount, String currency,
                                   String source, String org, String appraiser, String reportNo,
                                   LocalDateTime validFrom, LocalDateTime validUntil, String basis) {
        v.setAmount(amount);
        v.setCurrency((currency == null || currency.trim().isEmpty()) ? "CNY" : currency.trim());
        v.setValuationSource(source);
        v.setAppraiserOrg(org);
        v.setAppraiserName(appraiser);
        v.setReportNo(reportNo);
        v.setValidFrom(validFrom);
        v.setValidUntil(validUntil);
        v.setBasis(basis);
    }

    private void validatePeriod(LocalDateTime validFrom, LocalDateTime validUntil) {
        if (validFrom == null || validUntil == null) {
            throw new IllegalArgumentException("估值有效期起止时间不能为空");
        }
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("估值有效期截止时间必须晚于生效时间");
        }
    }

    private InsuranceValuation requireValuationByRelic(Long relicId) {
        InsuranceValuation valuation = valuationMapper.selectOne(
                new LambdaQueryWrapper<InsuranceValuation>()
                        .eq(InsuranceValuation::getRelicId, relicId));
        if (valuation == null) {
            throw new IllegalArgumentException("该文物尚未登记保险估值: relicId=" + relicId);
        }
        return valuation;
    }

    private InsuranceValuation requireValuation(Long id) {
        InsuranceValuation valuation = valuationMapper.selectById(id);
        if (valuation == null) {
            throw new IllegalArgumentException("保险估值记录不存在: id=" + id);
        }
        return valuation;
    }

    private InsuranceValuationVersion requireVersion(Long id) {
        InsuranceValuationVersion version = versionMapper.selectById(id);
        if (version == null) {
            throw new IllegalArgumentException("保险估值版本不存在: id=" + id);
        }
        return version;
    }

    private Map<String, Object> toMap(Object entity) {
        return objectMapper.convertValue(entity,
                new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private List<Map<String, Object>> toMap(List<?> entities) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object e : entities) {
            list.add(toMap(e));
        }
        return list;
    }
}
