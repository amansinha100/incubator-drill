/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Generated by http://code.google.com/p/protostuff/ ... DO NOT EDIT!
// Generated from protobuf

package org.apache.drill.exec.proto.beans;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import com.dyuproject.protostuff.GraphIOUtil;
import com.dyuproject.protostuff.Input;
import com.dyuproject.protostuff.Message;
import com.dyuproject.protostuff.Output;
import com.dyuproject.protostuff.Schema;

public final class BitToUserHandshake implements Externalizable, Message<BitToUserHandshake>, Schema<BitToUserHandshake>
{

    public static Schema<BitToUserHandshake> getSchema()
    {
        return DEFAULT_INSTANCE;
    }

    public static BitToUserHandshake getDefaultInstance()
    {
        return DEFAULT_INSTANCE;
    }

    static final BitToUserHandshake DEFAULT_INSTANCE = new BitToUserHandshake();

    
    private int rpcVersion;
    private HandshakeStatus status;
    private String errorId;
    private String errorMessage;
    private RpcEndpointInfos serverInfos;

    public BitToUserHandshake()
    {
        
    }

    // getters and setters

    // rpcVersion

    public int getRpcVersion()
    {
        return rpcVersion;
    }

    public BitToUserHandshake setRpcVersion(int rpcVersion)
    {
        this.rpcVersion = rpcVersion;
        return this;
    }

    // status

    public HandshakeStatus getStatus()
    {
        return status == null ? HandshakeStatus.SUCCESS : status;
    }

    public BitToUserHandshake setStatus(HandshakeStatus status)
    {
        this.status = status;
        return this;
    }

    // errorId

    public String getErrorId()
    {
        return errorId;
    }

    public BitToUserHandshake setErrorId(String errorId)
    {
        this.errorId = errorId;
        return this;
    }

    // errorMessage

    public String getErrorMessage()
    {
        return errorMessage;
    }

    public BitToUserHandshake setErrorMessage(String errorMessage)
    {
        this.errorMessage = errorMessage;
        return this;
    }

    // serverInfos

    public RpcEndpointInfos getServerInfos()
    {
        return serverInfos;
    }

    public BitToUserHandshake setServerInfos(RpcEndpointInfos serverInfos)
    {
        this.serverInfos = serverInfos;
        return this;
    }

    // java serialization

    public void readExternal(ObjectInput in) throws IOException
    {
        GraphIOUtil.mergeDelimitedFrom(in, this, this);
    }

    public void writeExternal(ObjectOutput out) throws IOException
    {
        GraphIOUtil.writeDelimitedTo(out, this, this);
    }

    // message method

    public Schema<BitToUserHandshake> cachedSchema()
    {
        return DEFAULT_INSTANCE;
    }

    // schema methods

    public BitToUserHandshake newMessage()
    {
        return new BitToUserHandshake();
    }

    public Class<BitToUserHandshake> typeClass()
    {
        return BitToUserHandshake.class;
    }

    public String messageName()
    {
        return BitToUserHandshake.class.getSimpleName();
    }

    public String messageFullName()
    {
        return BitToUserHandshake.class.getName();
    }

    public boolean isInitialized(BitToUserHandshake message)
    {
        return true;
    }

    public void mergeFrom(Input input, BitToUserHandshake message) throws IOException
    {
        for(int number = input.readFieldNumber(this);; number = input.readFieldNumber(this))
        {
            switch(number)
            {
                case 0:
                    return;
                case 2:
                    message.rpcVersion = input.readInt32();
                    break;
                case 3:
                    message.status = HandshakeStatus.valueOf(input.readEnum());
                    break;
                case 4:
                    message.errorId = input.readString();
                    break;
                case 5:
                    message.errorMessage = input.readString();
                    break;
                case 6:
                    message.serverInfos = input.mergeObject(message.serverInfos, RpcEndpointInfos.getSchema());
                    break;

                default:
                    input.handleUnknownField(number, this);
            }   
        }
    }


    public void writeTo(Output output, BitToUserHandshake message) throws IOException
    {
        if(message.rpcVersion != 0)
            output.writeInt32(2, message.rpcVersion, false);

        if(message.status != null)
             output.writeEnum(3, message.status.number, false);

        if(message.errorId != null)
            output.writeString(4, message.errorId, false);

        if(message.errorMessage != null)
            output.writeString(5, message.errorMessage, false);

        if(message.serverInfos != null)
             output.writeObject(6, message.serverInfos, RpcEndpointInfos.getSchema(), false);

    }

    public String getFieldName(int number)
    {
        switch(number)
        {
            case 2: return "rpcVersion";
            case 3: return "status";
            case 4: return "errorId";
            case 5: return "errorMessage";
            case 6: return "serverInfos";
            default: return null;
        }
    }

    public int getFieldNumber(String name)
    {
        final Integer number = __fieldMap.get(name);
        return number == null ? 0 : number.intValue();
    }

    private static final java.util.HashMap<String,Integer> __fieldMap = new java.util.HashMap<String,Integer>();
    static
    {
        __fieldMap.put("rpcVersion", 2);
        __fieldMap.put("status", 3);
        __fieldMap.put("errorId", 4);
        __fieldMap.put("errorMessage", 5);
        __fieldMap.put("serverInfos", 6);
    }
    
}
