create unique index IDX_LDAP_USER_ATTRIBUTE_UK_ATTRIBUTE_NAME on LDAP_USER_ATTRIBUTE (ATTRIBUTE_NAME) where DELETE_TS is null ;