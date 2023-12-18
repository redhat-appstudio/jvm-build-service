package com.redhat.hacbs.common.tools.repo;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

public class GithubCredentials extends CredentialsProvider {

    final String me;
    final String password;

    public GithubCredentials(String me) {
        this.me = me;
        String pw = null;
        File homeDir = new File(System.getProperty("user.home"));
        File propertyFile = new File(homeDir, ".github");
        try {
            if (propertyFile.exists()) {
                Properties props = new Properties();
                FileInputStream in = null;
                try {
                    in = new FileInputStream(propertyFile);
                    props.load(in);
                } finally {
                    IOUtils.closeQuietly(in);
                }
                String oauth = props.getProperty("oauth");
                String jwt = props.getProperty("jwt");
                String password = props.getProperty("password");
                if (oauth != null) {
                    pw = oauth;
                } else if (password != null) {
                    pw = password;
                } else if (jwt != null) {
                    pw = jwt;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        password = pw;
    }

    @Override
    public boolean isInteractive() {
        return false;
    }

    @Override
    public boolean supports(CredentialItem... items) {
        for (CredentialItem i : items) {
            if (i instanceof CredentialItem.InformationalMessage) {
                continue;
            }
            if (i instanceof CredentialItem.Username) {
                continue;
            }
            if (i instanceof CredentialItem.Password) {
                continue;
            }
            if (i instanceof CredentialItem.StringType) {
                if (i.getPromptText().equals("Password: ")) { //$NON-NLS-1$
                    continue;
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
        for (CredentialItem i : items) {
            if (i instanceof CredentialItem.InformationalMessage) {
                continue;
            }
            if (i instanceof CredentialItem.Username) {
                ((CredentialItem.Username) i).setValue(me);
                continue;
            }
            if (i instanceof CredentialItem.Password) {
                ((CredentialItem.Password) i)
                        .setValue(password.toCharArray());
                continue;
            }
            if (i instanceof CredentialItem.StringType) {
                if (i.getPromptText().equals("Password: ")) { //$NON-NLS-1$
                    ((CredentialItem.StringType) i).setValue(password);
                    continue;
                }
            }
            throw new UnsupportedCredentialItem(uri, i.getClass().getName()
                    + ":" + i.getPromptText()); //$NON-NLS-1$
        }
        return true;
    }
}
