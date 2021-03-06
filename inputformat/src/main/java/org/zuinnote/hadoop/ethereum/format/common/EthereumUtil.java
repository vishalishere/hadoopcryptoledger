/**
* Copyright 2017 ZuInnoTe (Jörn Franke) <zuinnote@gmail.com>
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
**/
package org.zuinnote.hadoop.ethereum.format.common;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.bind.DatatypeConverter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.zuinnote.hadoop.ethereum.format.common.rlp.RLPElement;
import org.zuinnote.hadoop.ethereum.format.common.rlp.RLPList;
import org.zuinnote.hadoop.ethereum.format.common.rlp.RLPObject;

/**
 *
 *
 */
public class EthereumUtil {
	public static final int RLP_OBJECTTYPE_INVALID = -1;
	public static final int RLP_OBJECTTYPE_ELEMENT = 0;
	public static final int RLP_OBJECTTYPE_LIST = 1;
	public static final int CHAIN_ID_INC = 35; // EIP-255, chainId encoded in V
	public static final int LOWER_REAL_V = 27; // EIP-255, chainId encoded in V
	public static final int HASH_SIZE = 256;

	private static final Log LOG = LogFactory.getLog(EthereumUtil.class.getName());
	/** RLP functionality for Ethereum: https://github.com/ethereum/wiki/wiki/RLP **/


/**
 * Read RLP data from a Byte Buffer.
 *  
 * @param bb ByteBuffer from which to read the RLP data
 * @return RLPObject (e.g. RLPElement or RLPList) containing RLP data
 */
public static RLPObject rlpDecodeNextItem(ByteBuffer bb) {
	// detect object type
	RLPObject result=null;
    int objType = detectRLPObjectType(bb);
    switch (objType) {
	    case EthereumUtil.RLP_OBJECTTYPE_ELEMENT:
	    		result=EthereumUtil.decodeRLPElement(bb);
	    		break;
	    case EthereumUtil.RLP_OBJECTTYPE_LIST:
	    		result=EthereumUtil.decodeRLPList(bb);
	    		break;
	    default: LOG.error("Unknown object type");
    }
	return result;
}

/**
 * Detects the object type of an RLP encoded object. Note that it does not modify the read position in the ByteBuffer.
 * 
 * 
 * @param bb ByteBuffer that contains RLP encoded object
 * @return object type: EthereumUtil.RLP_OBJECTTYPE_ELEMENT or EthereumUtil.RLP_OBJECTTYPE_LIST or EthereumUtil.RLP_OBJECTTYPE_INVALID
 */
public static int detectRLPObjectType(ByteBuffer bb) {
	bb.mark();
	byte detector = bb.get();
	int unsignedDetector=detector & 0xFF;
	int result = EthereumUtil.RLP_OBJECTTYPE_INVALID;
	if (unsignedDetector<=0x7f) {
		result=EthereumUtil.RLP_OBJECTTYPE_ELEMENT;
	} else
	if ((unsignedDetector>=0x80) && (unsignedDetector<=0xb7)) {
		result=EthereumUtil.RLP_OBJECTTYPE_ELEMENT;
	} else
		if ((unsignedDetector>=0xb8) && (unsignedDetector<=0xbf)) {
			result=EthereumUtil.RLP_OBJECTTYPE_ELEMENT;
		}
	else 
		if ((unsignedDetector>=0xc0) && (unsignedDetector<=0xf7)) {
			result=EthereumUtil.RLP_OBJECTTYPE_LIST;
		} else
			if ((unsignedDetector>=0xf8) && (unsignedDetector<=0xff)) {
				result=EthereumUtil.RLP_OBJECTTYPE_LIST;
			}
			else {
				result=EthereumUtil.RLP_OBJECTTYPE_INVALID;
				LOG.error("Invalid RLP object type. Internal error or not RLP Data");
	}
	bb.reset();
	return result;
}

/*
 * Decodes an RLPElement from the given ByteBuffer
 * 
 *  @param bb Bytebuffer containing an RLPElement
 *  
 *  @return RLPElement in case the byte stream represents a valid RLPElement, null if not
 * 
 */
private static RLPElement decodeRLPElement(ByteBuffer bb) {
	RLPElement result=null;
	byte firstByte = bb.get();
	int firstByteUnsigned = firstByte & 0xFF;

	if (firstByteUnsigned <= 0x7F) {
		result=new RLPElement(new byte[] {firstByte},new byte[] {firstByte});
	} else if ((firstByteUnsigned>=0x80) && (firstByteUnsigned<=0xb7)) {
		// read indicator
		byte[] indicator=new byte[]{firstByte};
		int noOfBytes = firstByteUnsigned - 0x80;
		// read raw data
		byte[] rawData = new byte[noOfBytes];
		if (noOfBytes > 0) {
			bb.get(rawData);
		}
		result=new RLPElement(indicator,rawData);
	} else if ((firstByteUnsigned>=0xb8) && (firstByteUnsigned<=0xbf)) {
		// read size of indicator (size of the size)
		int NoOfBytesSize = firstByteUnsigned-0xb7;
		byte[] indicator = new byte[NoOfBytesSize+1];
		indicator[0]=firstByte;
		bb.get(indicator, 1, NoOfBytesSize);
		// read the size of the data
		byte[] rawDataNumber=Arrays.copyOfRange(indicator, 1, indicator.length);
		ByteBuffer byteBuffer = ByteBuffer.wrap(rawDataNumber);
		long noOfBytes = 0;
		if (indicator.length<3) { // byte
			noOfBytes=byteBuffer.get() & 0xFF;
		} else if (indicator.length<4) { // short
			noOfBytes=byteBuffer.getShort();
		} else if (indicator.length<6) { // int
			noOfBytes=byteBuffer.getInt();
		} else if (indicator.length<10) { // long
			noOfBytes=byteBuffer.getLong();
		}

		// read the data
		byte[] rawData=new byte[(int) noOfBytes];
		bb.get(rawData);
		result= new RLPElement(indicator,rawData);
	} else {
		result=null;
	}
	return result;
}

private static byte[] encodeRLPElement(byte[] rawData) {
	byte[] result=null;
	if ((rawData==null) || (rawData.length==0)) {
		return new byte[] {(byte) 0x80};
	} else
	if (rawData.length<=55) {
			if ((rawData.length==1) && (rawData[0]<=(byte)0x7F)) {
				return new byte[] {rawData[0]};
			}
			 result=new byte[rawData.length+1];
			result[0]=(byte) (0x80+rawData.length);
			for (int i=0;i<rawData.length;i++) {
				result[i+1]=rawData[i];
			}
	} else {
		ByteBuffer bb = ByteBuffer.allocate(4);
		bb.order(ByteOrder.LITTLE_ENDIAN);
		bb.putInt(rawData.length);
		byte[] intArray = bb.array();
		int intSize=0;
		for (int i=0;i<intArray.length;i++) {
			if (intArray[i]==0) {
				break;
			} else {
				intSize++;
			}
		}
		 result = new byte[1+intSize+rawData.length];
		result[0]=(byte) (0xb7+intSize);
		for (int i=0;i<intSize;i++) {
			result[i+1]=intArray[i];
		}
		for (int i=0;i<rawData.length;i++) {
			result[1+intSize+i]=rawData[i];
		}
	}
	return result;
}

private static byte[] encodeRLPList(List<byte[]> rawElementList) {
	byte[] result;
	int totalSize=0;
	if ((rawElementList==null) || (rawElementList.size()==0)) {
		return new byte[] {(byte) 0xc0};
	}
	for (int i=0;i<rawElementList.size();i++) {
		totalSize+=rawElementList.get(i).length;
	}
	int currentPosition=0;
	if (totalSize<=55) {
		result = new byte[1+totalSize];
		result[0]=(byte) (0xc0+totalSize);
		currentPosition=1;
	} else {
		ByteBuffer bb = ByteBuffer.allocate(4);
		bb.order(ByteOrder.LITTLE_ENDIAN);
		bb.putInt(totalSize);
		byte[] intArray = bb.array();
		int intSize=0;
		for (int i=0;i<intArray.length;i++) {
			if (intArray[i]==0x00) {
				break;
			} else {
				intSize++;
			}
		}
		 result = new byte[1+intSize+totalSize];
		 result[0]=(byte) (0xf7+intSize & 0xFF);
		 for (int i=0;i<intSize;i++) {
			 result[i+1]=intArray[i];
		 }
		 currentPosition=1+intSize;
	}
	// copy list items
	for (int i=0;i<rawElementList.size();i++) {
		byte[] currentElement=rawElementList.get(i);
		for (int j=0;j<currentElement.length;j++) {
			result[currentPosition]=currentElement[j];
			currentPosition++;
		}
	}
	return result;
}

/**
 * Determines the size of a RLP list. Note: it does not change the position in the ByteBuffer
 * 
 * @param bb
 * @return -1 if not an RLP encoded list, otherwise size of list INCLUDING the prefix of the list (e.g. byte that indicates that it is a list and size of list in bytes) in bytes 
 */

public static long getRLPListSize(ByteBuffer bb) {
	long result=-1;
	bb.mark();
	byte detector = bb.get();
	int unsignedDetector=detector & 0xFF;
	if ((unsignedDetector>=0xc0) && (unsignedDetector<=0xf7)) {
		result=unsignedDetector; // small list
	} else
		if ((unsignedDetector>=0xf8) && (unsignedDetector<=0xff)) {
			// the first byte
			// large list
			// read size of indicator (size of the size)
			int noOfBytesSize = unsignedDetector-0xf7;
			byte[] indicator = new byte[noOfBytesSize+1];
			indicator[0]=detector;
			bb.get(indicator, 1, noOfBytesSize);
			result=indicator.length;
			// read the size of the data
			byte[] rawDataNumber=Arrays.copyOfRange(indicator, 1, indicator.length);
			ByteBuffer byteBuffer = ByteBuffer.wrap(rawDataNumber);
			if (indicator.length<3) { // byte
				result+=byteBuffer.get() & 0xFF;
			} else if (indicator.length<4) { // short
				result+=byteBuffer.getShort();
			} else if (indicator.length<6) { // int
				result+=byteBuffer.getInt();
			} else if (indicator.length<10) { // long
				result+=byteBuffer.getLong();
			}
			
		}
	bb.reset();
	return result;	
}


/*
 * Decodes an RLPList from the given ByteBuffer. This list may contain further RLPList and RLPElements that are decoded as well
 * 
 *  @param bb Bytebuffer containing an RLPList
 *  
 *  @return RLPList in case the byte stream represents a valid RLPList, null if not
 * 
 */
private static RLPList decodeRLPList(ByteBuffer bb) {

	byte firstByte = bb.get();
	int firstByteUnsigned = firstByte & 0xFF;
	long payloadSize=-1;
	if ((firstByteUnsigned>=0xc0) && (firstByteUnsigned<=0xf7)) {
		// length of the list in bytes
		int offsetSmallList = 0xc0 & 0xff;
		payloadSize=(long)(firstByteUnsigned) - offsetSmallList;
		
	} else if ((firstByteUnsigned>=0xf8) && (firstByteUnsigned<=0xff)) {
		// read size of indicator (size of the size)
		int noOfBytesSize = firstByteUnsigned-0xf7;
		byte[] indicator = new byte[noOfBytesSize+1];
		indicator[0]=firstByte;
		bb.get(indicator, 1, noOfBytesSize);
		// read the size of the data
		byte[] rawDataNumber=Arrays.copyOfRange(indicator, 1, indicator.length);
		ByteBuffer byteBuffer = ByteBuffer.wrap(rawDataNumber);
		if (indicator.length<3) { // byte
			payloadSize=byteBuffer.get() & 0xFF;
		} else if (indicator.length<4) { // short
			payloadSize=byteBuffer.getShort();
		} else if (indicator.length<6) { // int
			payloadSize=byteBuffer.getInt();
		} else if (indicator.length<10) { // long
			payloadSize=byteBuffer.getLong();
		} else {
			LOG.error("Invalid indicator");
		}
	} else {
		LOG.error("Invalid RLP encoded list detected");
	}
	ArrayList<RLPObject> payloadList=new ArrayList<>();
	if (payloadSize>0) {
		byte[] payload=new byte[(int) payloadSize];
		bb.get(payload);
		ByteBuffer payloadBB=ByteBuffer.wrap(payload);
		while(payloadBB.remaining()>0) {
			switch (EthereumUtil.detectRLPObjectType(payloadBB)) {
			 case EthereumUtil.RLP_OBJECTTYPE_ELEMENT:
		    		payloadList.add(EthereumUtil.decodeRLPElement(payloadBB));
		    		break;
		    case EthereumUtil.RLP_OBJECTTYPE_LIST:
		    		payloadList.add(EthereumUtil.decodeRLPList(payloadBB));
		    		break;
		    default: LOG.error("Unknown object type");
			
			}
			
		}
	} 
	return new RLPList(payloadList);
}

/*** Ethereum-specific functionaloity **/


/**
 * Calculates the chain Id
 * 
 * @param eTrans Ethereum Transaction of which the chain id should be calculated
 * @return chainId: 0, Ethereum testnet (aka Olympic); 1: Ethereum mainet (aka Frontier, Homestead, Metropolis) - also Classic (from fork) -also Expanse (alternative Ethereum implementation), 2 Morden (Ethereum testnet, now Ethereum classic testnet), 3 Ropsten public cross-client Ethereum testnet, 4: Rinkeby Geth Ethereum testnet, 42 Kovan, public Parity Ethereum testnet, 7762959 Musicoin, music blockchain
 */
public static Long calculateChainId(EthereumTransaction eTrans) {
	Long result=null;
		long rawResult=EthereumUtil.convertToInt(new RLPElement(new byte[0],eTrans.getSig_v()));
		if (!((rawResult == EthereumUtil.LOWER_REAL_V) || (rawResult== (LOWER_REAL_V+1)))) {
			result = (rawResult-EthereumUtil.CHAIN_ID_INC)/2;
	}
	return result;
}


/***
 * Calculates the hash of a transaction
 * 
 * @param eTrans transaction
 * @return transaction hash as KECCAK-256
 */
public static byte[] getTransactionHash(EthereumTransaction eTrans) {
	ArrayList<byte[]> rlpTransaction = new ArrayList<>();
	rlpTransaction.add(EthereumUtil.encodeRLPElement(eTrans.getNonce()));
	rlpTransaction.add(EthereumUtil.encodeRLPElement(eTrans.getGasPrice()));
	rlpTransaction.add(EthereumUtil.encodeRLPElement(eTrans.getGasLimit()));
	rlpTransaction.add(EthereumUtil.encodeRLPElement(eTrans.getReceiveAddress()));
	rlpTransaction.add(EthereumUtil.encodeRLPElement(eTrans.getValue()));
	rlpTransaction.add(EthereumUtil.encodeRLPElement(eTrans.getData()));
	rlpTransaction.add(EthereumUtil.encodeRLPElement(eTrans.getSig_v()));
	rlpTransaction.add(EthereumUtil.encodeRLPElement(eTrans.getSig_r()));
	rlpTransaction.add(EthereumUtil.encodeRLPElement(eTrans.getSig_s()));
	byte[] transEnc = EthereumUtil.encodeRLPList(rlpTransaction);
	Keccak.Digest256 digest = new Keccak.Digest256();
	digest.update(transEnc,0,transEnc.length);
	return digest.digest();
}

/** Data types conversions for Ethereum **/

/***
 * Converts a variable size number (e.g. byte,short,int,long) in a RLPElement to long
 *  
 * @param rpe RLPElement containing a number
 * @return number as long or null if not a correct number
 */
public static Long convertVarNumberToLong(RLPElement rpe) {
		Long result=0L;
		if (rpe.getRawData()!=null) {
			if (rpe.getRawData().length==0) {
				result=0L;
			} else
			if (rpe.getRawData().length<2) {
				result=(long) EthereumUtil.convertToByte(rpe);
			} else if (rpe.getRawData().length<3) {
				result = (long) EthereumUtil.convertToShort(rpe);
			} else if (rpe.getRawData().length<5) {
				result=(long) EthereumUtil.convertToInt(rpe);
			} else if (rpe.getRawData().length<9) {
				result=EthereumUtil.convertToLong(rpe);
			}
		}
		return result;
}

/**
 * Converts a byte in a RLPElement to byte
 * 
 * @param rpe RLP element containing a raw byte
 * @return byte or null if not byte
 */

public static Byte convertToByte(RLPElement rpe) {
	Byte result=0;
	if ((rpe.getRawData()!=null) || (rpe.getRawData().length==1)) {
			result=rpe.getRawData()[0];
	} 
	return result;
}

/**
 * Converts a short in a RLPElement to short
 * 
 * @param rpe RLP element containing a raw short
 * @return short or null if not short
 */

public static Short convertToShort(RLPElement rpe) {
	Short result=0;
	byte[] rawBytes=rpe.getRawData();
	int dtSize=2;
	if ((rawBytes!=null)) {
			// fill leading zeros
			if (rawBytes.length<dtSize) {
				byte[] fullBytes=new byte[dtSize];
				int dtDiff=dtSize-rawBytes.length;
				for (int i=0;i<rawBytes.length;i++) {
					fullBytes[dtDiff+i]=rawBytes[i];
					result=ByteBuffer.wrap(fullBytes).getShort();
				}
			} else {
				result=ByteBuffer.wrap(rawBytes).getShort();
			}
	}
	return result;
}

/**
 * Converts a int in a RLPElement to int
 * 
 * @param rpe RLP element containing a raw int
 * @return int or null if not int
 */

public static Integer convertToInt(RLPElement rpe) {
	Integer result=0;
	byte[] rawBytes=rpe.getRawData();
	int dtSize=4;
	if ((rawBytes!=null)) {
			// fill leading zeros
			if (rawBytes.length<dtSize) {
				byte[] fullBytes=new byte[dtSize];
				int dtDiff=dtSize-rawBytes.length;
				for (int i=0;i<rawBytes.length;i++) {
					fullBytes[dtDiff+i]=rawBytes[i];
					result=ByteBuffer.wrap(fullBytes).getInt();
				}
			} else {
				result=ByteBuffer.wrap(rawBytes).getInt();
			}
	}
	return result;
}

/**
 * Converts a long in a RLPElement to long
 * 
 * @param rpe RLP element containing a raw long
 * @return long or null if not long
 */

public static Long convertToLong(RLPElement rpe) {
	Long result=0L;
	byte[] rawBytes=rpe.getRawData();
	int dtSize=8;
	if ((rawBytes!=null)) {
			// fill leading zeros
			if (rawBytes.length<dtSize) {
				byte[] fullBytes=new byte[dtSize];
				int dtDiff=dtSize-rawBytes.length;
				for (int i=0;i<rawBytes.length;i++) {
					fullBytes[dtDiff+i]=rawBytes[i];
					result=ByteBuffer.wrap(fullBytes).getLong();
				}
			} else {
				result=ByteBuffer.wrap(rawBytes).getLong();
			}
	}
	return result;
}


/***
 * Converts a UTF-8 String in a RLPElement to String
 * 
 * @param rpe RLP element containing a raw String
 * @return string or null if not String
 * @throws UnsupportedEncodingException if UTF-8 is not supported
 */

public static String convertToString(RLPElement rpe) throws UnsupportedEncodingException {
	String result=null;
	if (!((rpe.getRawData()==null) || (rpe.getRawData().length==0))) {
			result=new String(rpe.getRawData(), "UTF-8");
	} 
	return result;
}

/***
 * Converts a String in a RLPElement to String
 * 
 * @param rpe RLP element containing a raw String
 * @param encoding encoding of the raw String
 * @return string or null if not String
 * @throws UnsupportedEncodingException 
 */

public static String convertToString(RLPElement rpe, String encoding) throws UnsupportedEncodingException {
	String result=null;
	if (!((rpe.getRawData()==null) || (rpe.getRawData().length==0))) {
			result=new String(rpe.getRawData(), encoding);
	} 
	return result;
}

/** Hex functionality **/
/**
* Converts a Hex String to Byte Array. Only used for configuration not for parsing. Hex String is in format of xsd:hexBinary
*
* @param hexString String in Hex format.
*
* @return byte array corresponding to String in Hex format
*
*/
public static byte[] convertHexStringToByteArray(String hexString) {
    return DatatypeConverter.parseHexBinary(hexString);
}


/**
* Converts a Byte Array to Hex String. Only used for configuration not for parsing. Hex String is in format of xsd:hexBinary
*
* @param byteArray byte array to convert
*
* @return String in Hex format corresponding to byteArray
*
*/
public static String convertByteArrayToHexString(byte[] byteArray) {
    return DatatypeConverter.printHexBinary(byteArray);
}


/**
* Reverses the order of the byte array
*
* @param inputByteArray array to be reversed
*
* @return inputByteArray in reversed order
*
**/
public static byte[] reverseByteArray(byte[] inputByteArray) {
	byte[] result=new byte[inputByteArray.length];
	for (int i=inputByteArray.length-1;i>=0;i--) {
		result[result.length-1-i]=inputByteArray[i];
	}
	return result;
}


}
