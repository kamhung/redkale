/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.mq;

import org.redkale.util.AnyValue;

/**
 * 自定义的MessageAgent加载器
 *
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @since 2.4.0
 */
public interface MessageAgentLoader {

    public boolean match(AnyValue config);

    public Class<? extends MessageAgent> agentClass();
}
