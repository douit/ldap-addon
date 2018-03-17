package com.haulmont.addon.ldap.core.dao;

import com.haulmont.addon.ldap.core.rule.ApplyMatchingRuleContext;
import com.haulmont.addon.ldap.core.rule.appliers.DbStoredMatchingRuleProcessor;
import com.haulmont.addon.ldap.core.utils.LdapHelper;
import com.haulmont.addon.ldap.entity.*;
import com.haulmont.cuba.core.EntityManager;
import com.haulmont.cuba.core.Persistence;
import com.haulmont.cuba.core.global.Metadata;
import com.haulmont.cuba.core.global.PersistenceHelper;
import com.haulmont.cuba.security.entity.Group;
import com.haulmont.cuba.security.entity.Role;
import com.haulmont.cuba.security.entity.User;
import com.haulmont.cuba.security.entity.UserRole;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.inject.Inject;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchResult;
import javax.print.attribute.Attribute;
import javax.print.attribute.standard.MediaSize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.haulmont.addon.ldap.core.dao.UserSynchronizationLogDao.NAME;
import static com.haulmont.addon.ldap.entity.MatchingRuleType.*;
import static com.haulmont.addon.ldap.entity.UserSynchronizationResultEnum.*;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

@Service(NAME)
public class UserSynchronizationLogDao {

    public final static String NAME = "ldap_UserSynchronizationLogDao";

    public final String INDENT = "   ";

    @Inject
    private Persistence persistence;

    @Inject
    private Metadata metadata;


    public void saveUserSynchronizationLog(UserSynchronizationLog userSynchronizationLog) {
        EntityManager entityManager = persistence.getEntityManager();
        UserSynchronizationLog mergedUserSynchronizationLog = PersistenceHelper.isNew(userSynchronizationLog) ? userSynchronizationLog : entityManager.merge(userSynchronizationLog);
        entityManager.persist(mergedUserSynchronizationLog);
    }

    @Transactional
    public void logUserSynchronization(ApplyMatchingRuleContext applyMatchingRuleContext, User originalUser) {
        User cubaUser = applyMatchingRuleContext.getCubaUser();
        UserSynchronizationLog userSynchronizationLog = metadata.create(UserSynchronizationLog.class);
        userSynchronizationLog.setLogin(cubaUser.getLogin());
        userSynchronizationLog.setResult(SuccessSync);
        userSynchronizationLog.setLdapAttributes(getLdapAttributes(applyMatchingRuleContext.getLdapUserAttributes()));
        userSynchronizationLog.setAccessGroupBefore(originalUser.getGroup() == null ? null : originalUser.getGroup().getName());
        userSynchronizationLog.setAccessGroupAfter(cubaUser.getGroup() == null ? null : cubaUser.getGroup().getName());
        userSynchronizationLog.setRolesBefore(getRolesField(originalUser.getUserRoles()));
        userSynchronizationLog.setRolesAfter(getRolesField(cubaUser.getUserRoles()));
        userSynchronizationLog.setUserInfoBefore(getUserInfoField(originalUser));
        userSynchronizationLog.setUserInfoAfter(getUserInfoField(cubaUser));
        userSynchronizationLog.setAppliedRules(getAppliedRulesField(applyMatchingRuleContext.getAppliedRules()));
        userSynchronizationLog.setIsNewUser(PersistenceHelper.isNew(cubaUser));
        if (FALSE.equals(cubaUser.getActive()) && TRUE.equals(originalUser.getActive())) {
            userSynchronizationLog.setIsDeactivated(TRUE);
        }
        saveUserSynchronizationLog(userSynchronizationLog);
    }

    @Transactional
    public void logLoginError(String login, Exception e) {
        UserSynchronizationLog userSynchronizationLog = metadata.create(UserSynchronizationLog.class);
        userSynchronizationLog.setLogin(login);
        userSynchronizationLog.setResult(LdapLoginError);
        userSynchronizationLog.setErrorText(ExceptionUtils.getFullStackTrace(e));
        saveUserSynchronizationLog(userSynchronizationLog);
    }

    @Transactional
    public void logSynchronizationError(String login, Exception e) {
        UserSynchronizationLog userSynchronizationLog = metadata.create(UserSynchronizationLog.class);
        userSynchronizationLog.setLogin(login);
        userSynchronizationLog.setResult(ErrorSync);
        userSynchronizationLog.setErrorText(ExceptionUtils.getFullStackTrace(e));
        saveUserSynchronizationLog(userSynchronizationLog);
    }

    private String getRolesField(List<UserRole> originalRoles) {
        StringBuilder sb = new StringBuilder();
        for (UserRole ur : originalRoles) {
            sb.append(ur.getRole().getName());
            sb.append("\n");
        }
        return sb.toString();
    }

    private String getUserInfoField(User originalUser) {
        StringBuilder sb = new StringBuilder();
        sb.append("Name:");
        sb.append(originalUser.getName());
        sb.append("\n");

        sb.append("Last name:");
        sb.append(originalUser.getLastName());
        sb.append("\n");

        sb.append("Email:");
        sb.append(originalUser.getEmail());
        sb.append("\n");

        sb.append("Position:");
        sb.append(originalUser.getPosition());
        sb.append("\n");

        sb.append("Language:");
        sb.append(originalUser.getLanguage());
        sb.append("\n");

        sb.append("Active:");
        sb.append(originalUser.getActive());

        return sb.toString();
    }

    private String getAppliedRulesField(Set<CommonMatchingRule> appliedRules) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (CommonMatchingRule cmr : appliedRules) {
            sb.append(i);
            sb.append(")");
            sb.append(cmr.getRuleType().getName());
            sb.append(". ");
            sb.append(cmr.getDescription());
            sb.append("(");
            sb.append(cmr.getMatchingRuleId());
            sb.append(")");
            sb.append("\n");
            if (!CUSTOM.equals(cmr.getRuleType())) {
                AbstractDbStoredMatchingRule dbRule = (AbstractDbStoredMatchingRule) cmr;
                sb.append(INDENT);
                sb.append("Override existing group: ");
                sb.append(dbRule.getIsOverrideExistingAccessGroup());
                sb.append(". ");

                sb.append("Override existing roles: ");
                sb.append(dbRule.getIsOverrideExistingRoles());
                sb.append(". ");

                sb.append("Terminal rule: ");
                sb.append(dbRule.getIsOverrideExistingRoles());
                sb.append(".");

                sb.append("\n");
                sb.append(INDENT);
                sb.append("Group: ");
                sb.append(dbRule.getAccessGroup() == null ? StringUtils.EMPTY : dbRule.getAccessGroup().getName());
                sb.append(".");

                sb.append("\n");
                sb.append(INDENT);
                sb.append("Roles: ");
                sb.append(dbRule.getRoles().stream().map(Role::getName).collect(Collectors.joining(",")));
                sb.append(".");

                if (!DEFAULT.equals(cmr.getRuleType())) {
                    sb.append("\n");
                    sb.append(INDENT);
                    sb.append("Additional rule info: ");
                    sb.append(getAdditionalRuleInfo(dbRule));
                }


            }
            sb.append("\n");
            i++;
        }
        return sb.toString();
    }

    private String getAdditionalRuleInfo(AbstractDbStoredMatchingRule dbRule) {
        StringBuilder sb = new StringBuilder();
        if (SIMPLE.equals(dbRule.getRuleType())) {
            SimpleMatchingRule smr = (SimpleMatchingRule) dbRule;
            sb.append(smr.getConditions().stream().map(SimpleRuleCondition::getAttributePair).collect(Collectors.joining(",")));
        } else if (SCRIPTING.equals(dbRule.getRuleType())) {
            ScriptingMatchingRule smr = (ScriptingMatchingRule) dbRule;
            sb.append(smr.getScriptingCondition());
        }
        return sb.toString();
    }

    private String getLdapAttributes(Attributes ldapAttributes) {
        StringBuilder sb = new StringBuilder();
        try {
            NamingEnumeration<String> attrs = ldapAttributes.getIDs();
            while (attrs.hasMore()) {
                String attrName = attrs.next();
                NamingEnumeration values = ldapAttributes.get(attrName).getAll();
                sb.append(attrName);
                sb.append(":");
                List<String> attrValues = new ArrayList<>();
                while (values.hasMore()) {
                    Object attr = values.next();
                    if (attr != null) {
                        attrValues.add(attr.toString());
                    }
                }
                sb.append(attrValues.stream().collect(Collectors.joining(",")));
                sb.append("\n");
            }
        } catch (NamingException e) {
            throw new RuntimeException(e);
        }
        return sb.toString();
    }
}
