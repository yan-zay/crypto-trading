package com.tj.crypto.entity.base;

import java.io.Serializable;

/**
 *
 */
public interface User extends Serializable {

    String getCreator();
    void setCreator(String creator);

    String getUpdater();
    void setUpdater(String updater);

}
